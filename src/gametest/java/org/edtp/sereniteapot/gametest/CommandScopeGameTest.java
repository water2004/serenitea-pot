package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Attacks the pot command boundary through selectors and nested command-source transforms.
 *
 * <p>The assertions deliberately observe command side effects. They do not depend on the
 * implementation's mixin locations, allow-list representation, or command-tree rewriting.</p>
 */
public final class CommandScopeGameTest {
    private static final String SORT_TARGET = "sereniteapot_scope_sort_target";
    private static final String SELECTOR_TARGET = "sereniteapot_scope_selector_target";
    private static final String RANDOM_SELECTED = "sereniteapot_scope_random_selected";
    private static final String KILL_ALL_TARGET = "sereniteapot_scope_kill_all";
    private static final String KILL_ENTITY_TARGET = "sereniteapot_scope_kill_entity";
    private static final BlockPos OWNER_SETBLOCK = new BlockPos(2, 100, 2);
    private static final BlockPos EXECUTE_AS_TARGET = new BlockPos(31, 100, 31);
    private static final BlockPos EXECUTE_AT_TARGET = new BlockPos(32, 100, 32);
    private static final BlockPos EXECUTE_IN_TARGET = new BlockPos(33, 100, 33);
    private static final BlockPos OTHER_POT_TARGET = new BlockPos(34, 100, 34);
    private static final BlockPos EXECUTE_RELATION_TARGET = new BlockPos(36, 100, 36);
    @GameTest(maxTicks = 300)
    public void containsOwnerAndOperatorCommandsWithoutChangingPublicCommands(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean complete = new AtomicBoolean();

        server.execute(() -> {
            Fixture fixture = null;
            try {
                fixture = Fixture.create(helper);
                verifyOwnerCommandsAreOpenAndScoped(server, fixture);
                verifyOperatorScopeUsesTheOccupiedPot(server, fixture);
                verifyOutsideAndConsoleKeepVanillaScope(server, fixture);
                verifyKillSelectorsAreFiltered(server, fixture);
                verifyLeaveRemainsAvailable(server, fixture);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                if (fixture != null) {
                    try {
                        fixture.close();
                    } catch (Throwable cleanupFailure) {
                        Throwable primary = failure.get();
                        if (primary == null) {
                            failure.set(cleanupFailure);
                        } else {
                            primary.addSuppressed(cleanupFailure);
                        }
                    }
                }
                complete.set(true);
            }
        });

        helper.onEachTick(() -> {
            if (!complete.get()) return;
            Throwable throwable = failure.get();
            if (throwable == null) {
                helper.succeed();
            } else {
                helper.fail("Command scope attack failed: " + throwable);
            }
        });
    }

    private static void verifyOwnerCommandsAreOpenAndScoped(MinecraftServer server, Fixture fixture) {
        ServerPlayer owner = fixture.owner;
        ServerLevel potOverworld = fixture.ownerPot.get(SereniteaPotDimension.OVERWORLD);
        ServerLevel potNether = fixture.ownerPot.get(SereniteaPotDimension.NETHER);
        ServerLevel otherPot = fixture.otherPot.get(SereniteaPotDimension.OVERWORLD);
        ServerLevel publicWorld = server.overworld();

        owner.setServerLevel(potOverworld);
        owner.snapTo(0.5, 100.0, 0.5);
        var commandRoot = server.getCommands().getDispatcher().getRoot();
        var ownerSource = owner.createCommandSourceStack();
        for (String globalCommand : List.of(
            "gamemode", "worldborder", "op", "whitelist", "reload", "scoreboard", "function")) {
            require(!commandRoot.getChild(globalCommand).canUse(ownerSource),
                "The owner whitelist accidentally granted /" + globalCommand);
        }
        owner.setGameMode(GameType.CREATIVE);
        run(server, owner, "gamemode survival @s");
        require(owner.gameMode.isCreative(),
            "A pot owner used /gamemode to leave the enforced creative mode");
        double borderSize = potOverworld.getWorldBorder().getSize();
        run(server, owner, "worldborder set 10000");
        require(potOverworld.getWorldBorder().getSize() == borderSize,
            "A non-operator owner bypassed the configured radius with /worldborder");
        clearAt(OWNER_SETBLOCK, potOverworld, publicWorld, otherPot);
        run(server, owner, "setblock 2 100 2 minecraft:diamond_block");
        requireBlock(potOverworld, OWNER_SETBLOCK, Blocks.DIAMOND_BLOCK,
            "A non-operator owner could not use /setblock inside their pot");
        requireBlock(publicWorld, OWNER_SETBLOCK, Blocks.AIR,
            "The owner's /setblock escaped to the public world");

        int ownerDiamonds = owner.getInventory().countItem(Items.DIAMOND);
        run(server, owner, "give @s minecraft:diamond 1");
        require(owner.getInventory().countItem(Items.DIAMOND) == ownerDiamonds + 1,
            "A non-operator owner could not use /give on an in-pot target");
        run(server, owner, "tp @s 4 100 4");
        require(owner.blockPosition().equals(new BlockPos(4, 100, 4)),
            "A non-operator owner could not use /tp on an in-pot target");

        // Public and foreign-pot targets are numerically nearer than the legal target.
        // Filtering after sort/limit would therefore kill an illegal target or no target.
        ServerPlayer publicNearest = fixture.outside;
        ServerPlayer foreignNext = fixture.otherOwner;
        ServerPlayer localFarther = fixture.operator;
        publicNearest.setServerLevel(publicWorld);
        foreignNext.setServerLevel(otherPot);
        localFarther.setServerLevel(potNether);
        publicNearest.snapTo(0.6, 100.0, 0.5);
        foreignNext.snapTo(0.7, 100.0, 0.5);
        localFarther.snapTo(12.0, 100.0, 0.5);
        for (ServerPlayer target : List.of(publicNearest, foreignNext, localFarther)) {
            target.addTag(SORT_TARGET);
            target.removeEffect(MobEffects.GLOWING);
        }
        run(server, owner,
            "effect give @a[tag=" + SORT_TARGET + ",sort=nearest,limit=1] minecraft:glowing 5 0 true");
        require(localFarther.hasEffect(MobEffects.GLOWING),
            "Selector scope was applied after sort/limit instead of before it");
        require(!publicNearest.hasEffect(MobEffects.GLOWING),
            "An owner command selected a player in the public world");
        require(!foreignNext.hasEffect(MobEffects.GLOWING),
            "An owner command selected a player in another pot");
        for (ServerPlayer target : List.of(publicNearest, foreignNext, localFarther)) {
            target.removeTag(SORT_TARGET);
            target.removeEffect(MobEffects.GLOWING);
        }

        // A literal UUID takes EntitySelector's global fast path in Vanilla. It must still
        // pass through exactly the same realm predicate as @a/@e.
        run(server, owner,
            "effect give " + fixture.outside.getUUID() + " minecraft:glowing 5 0 true");
        require(!fixture.outside.hasEffect(MobEffects.GLOWING),
            "A literal player UUID escaped the pot command scope");

        // GameTest mock players share this literal profile name. The outside player was
        // registered first, so a global name lookup resolves outside the pot.
        run(server, owner,
            "effect give " + fixture.outside.getGameProfile().name() + " minecraft:glowing 5 0 true");
        require(!fixture.outside.hasEffect(MobEffects.GLOWING),
            "A literal player name escaped the pot command scope");
        int namedOutsideDiamonds = fixture.outside.getInventory().countItem(Items.DIAMOND);
        run(server, owner,
            "give " + fixture.outside.getGameProfile().name() + " minecraft:diamond 1");
        require(fixture.outside.getInventory().countItem(Items.DIAMOND) == namedOutsideDiamonds,
            "A literal name in a player-only argument escaped the pot command scope");

        fixture.owner.removeEffect(MobEffects.GLOWING);
        fixture.operator.removeEffect(MobEffects.GLOWING);
        fixture.outside.removeEffect(MobEffects.GLOWING);
        fixture.otherOwner.removeEffect(MobEffects.GLOWING);
        fixture.operator.setServerLevel(potNether);
        fixture.otherOwner.setServerLevel(otherPot);
        run(server, owner, "effect give @a minecraft:glowing 5 0 true");
        require(fixture.owner.hasEffect(MobEffects.GLOWING),
            "@a did not include the command's in-pot owner");
        require(fixture.operator.hasEffect(MobEffects.GLOWING),
            "@a did not include another dimension of the same pot");
        require(!fixture.outside.hasEffect(MobEffects.GLOWING),
            "@a included a public-world player");
        require(!fixture.otherOwner.hasEffect(MobEffects.GLOWING),
            "@a included a player from another pot");
        fixture.owner.removeEffect(MobEffects.GLOWING);
        fixture.operator.removeEffect(MobEffects.GLOWING);

        verifyEveryVanillaSelectorKind(server, fixture);

        fixture.outside.snapTo(50.5, 100.0, 50.5);
        var outsidePosition = fixture.outside.position();
        run(server, owner, "tp " + fixture.outside.getUUID() + " 0 100 0");
        require(fixture.outside.position().equals(outsidePosition),
            "/tp targeted a player outside the current pot");
        int outsideDiamonds = fixture.outside.getInventory().countItem(Items.DIAMOND);
        run(server, owner, "give " + fixture.outside.getUUID() + " minecraft:diamond 1");
        require(fixture.outside.getInventory().countItem(Items.DIAMOND) == outsideDiamonds,
            "/give targeted a player outside the current pot");
    }

    /** Covers all six Vanilla selector prefixes; name and UUID fast paths are tested above. */
    private static void verifyEveryVanillaSelectorKind(MinecraftServer server, Fixture fixture) {
        ServerLevel currentPot = fixture.ownerPot.get(SereniteaPotDimension.OVERWORLD);
        ServerLevel foreignPot = fixture.otherPot.get(SereniteaPotDimension.OVERWORLD);
        fixture.owner.setServerLevel(currentPot);
        fixture.owner.snapTo(0.5, 100.0, 0.5);
        fixture.operator.setServerLevel(currentPot);
        fixture.operator.snapTo(12.0, 100.0, 0.5);
        fixture.outside.setServerLevel(server.overworld());
        fixture.outside.snapTo(0.6, 100.0, 0.5);
        fixture.otherOwner.setServerLevel(foreignPot);
        fixture.otherOwner.snapTo(0.7, 100.0, 0.5);

        List<ServerPlayer> candidates =
            List.of(fixture.operator, fixture.outside, fixture.otherOwner);
        for (ServerPlayer candidate : candidates) {
            candidate.addTag(SELECTOR_TARGET);
            candidate.removeTag(RANDOM_SELECTED);
            candidate.removeEffect(MobEffects.GLOWING);
        }

        // @p and @n have illegal numerically-nearer candidates. Scope filtering must
        // happen before their built-in nearest/limit processing.
        for (String selector : List.of(
            "@p[tag=" + SELECTOR_TARGET + "]",
            "@n[type=minecraft:player,tag=" + SELECTOR_TARGET + "]",
            "@e[type=minecraft:player,tag=" + SELECTOR_TARGET + "]"
        )) {
            run(server, fixture.owner,
                "effect give " + selector + " minecraft:glowing 5 0 true");
            require(fixture.operator.hasEffect(MobEffects.GLOWING),
                selector + " did not select the legal in-pot entity");
            require(!fixture.outside.hasEffect(MobEffects.GLOWING),
                selector + " selected an entity in the public world");
            require(!fixture.otherOwner.hasEffect(MobEffects.GLOWING),
                selector + " selected an entity in another pot");
            fixture.operator.removeEffect(MobEffects.GLOWING);
        }

        // Only one candidate remains after scoping, so @r is deterministic here.
        run(server, fixture.owner,
            "tag @r[tag=" + SELECTOR_TARGET + "] add " + RANDOM_SELECTED);
        require(fixture.operator.entityTags().contains(RANDOM_SELECTED),
            "@r did not select the only legal in-pot player");
        require(!fixture.outside.entityTags().contains(RANDOM_SELECTED),
            "@r selected a player in the public world");
        require(!fixture.otherOwner.entityTags().contains(RANDOM_SELECTED),
            "@r selected a player in another pot");

        for (ServerPlayer candidate : candidates) {
            candidate.removeTag(SELECTOR_TARGET);
            candidate.removeTag(RANDOM_SELECTED);
            candidate.removeEffect(MobEffects.GLOWING);
        }
    }

    private static void verifyOperatorScopeUsesTheOccupiedPot(MinecraftServer server, Fixture fixture) {
        ServerPlayer operator = fixture.operator;
        ServerLevel potOverworld = fixture.ownerPot.get(SereniteaPotDimension.OVERWORLD);
        ServerLevel potNether = fixture.ownerPot.get(SereniteaPotDimension.NETHER);
        ServerLevel otherPot = fixture.otherPot.get(SereniteaPotDimension.OVERWORLD);
        ServerLevel publicWorld = server.overworld();

        // This is a real op-list entry, not a source with a temporary permission override.
        server.getPlayerList().op(
            new NameAndId(operator.getGameProfile()),
            Optional.of(LevelBasedPermissionSet.OWNER),
            Optional.empty()
        );
        require(server.getPlayerList().isOp(new NameAndId(operator.getGameProfile())),
            "The attack fixture did not create a real operator");
        operator.setServerLevel(potNether);
        operator.snapTo(0.5, 100.0, 0.5);

        clearAt(EXECUTE_AS_TARGET, potOverworld, publicWorld, otherPot);
        run(server, operator,
            "execute as " + fixture.outside.getUUID()
                + " run setblock 31 100 31 minecraft:redstone_block");
        requireAirEverywhere(EXECUTE_AS_TARGET, potOverworld, publicWorld, otherPot,
            "/execute as acquired an out-of-pot entity");

        clearAt(EXECUTE_AT_TARGET, potOverworld, publicWorld, otherPot);
        run(server, operator,
            "execute at " + fixture.outside.getUUID()
                + " run setblock 32 100 32 minecraft:redstone_block");
        requireAirEverywhere(EXECUTE_AT_TARGET, potOverworld, publicWorld, otherPot,
            "/execute at acquired an out-of-pot entity");

        run(server, operator,
            "execute if entity " + fixture.outside.getUUID()
                + " run setblock 32 100 32 minecraft:redstone_block");
        requireAirEverywhere(EXECUTE_AT_TARGET, potOverworld, publicWorld, otherPot,
            "/execute if entity observed an out-of-pot entity");

        // "on origin" has no selector to filter. A local projectile can still point to an
        // entity outside the pot, so the final transformed source must be rejected centrally.
        Snowball projectile = new Snowball(
            potNether, 4.0, 100.0, 4.0, new ItemStack(Items.SNOWBALL));
        projectile.setOwner(fixture.outside);
        require(potNether.addFreshEntity(projectile),
            "Could not add the /execute on origin test projectile");
        require(projectile.getOwner() == fixture.outside,
            "The /execute on origin fixture did not retain its out-of-pot origin");
        clearAt(
            EXECUTE_RELATION_TARGET, potOverworld, potNether, publicWorld, otherPot);
        run(server, operator,
            "execute as " + projectile.getUUID()
                + " on origin run setblock 36 100 36 minecraft:redstone_block");
        requireAirEverywhere(
            EXECUTE_RELATION_TARGET,
            potOverworld,
            publicWorld,
            otherPot,
            "/execute on origin acquired an out-of-pot source");
        requireBlock(potNether, EXECUTE_RELATION_TARGET, Blocks.AIR,
            "/execute on origin changed the current pot Nether");
        projectile.discard();

        // Vanilla dimension aliases inside a pot are aliases for that pot's bundle,
        // never an exit into the server's public dimensions.
        clearAt(EXECUTE_IN_TARGET, potOverworld, publicWorld, otherPot);
        run(server, operator,
            "execute in minecraft:overworld run setblock 33 100 33 minecraft:emerald_block");
        requireBlock(potOverworld, EXECUTE_IN_TARGET, Blocks.EMERALD_BLOCK,
            "/execute in minecraft:overworld did not resolve to the occupied pot's overworld");
        requireBlock(publicWorld, EXECUTE_IN_TARGET, Blocks.AIR,
            "/execute in minecraft:overworld escaped to the public overworld");

        clearAt(OTHER_POT_TARGET, potOverworld, publicWorld, otherPot);
        run(server, operator,
            "execute in " + otherPot.dimension().identifier()
                + " run setblock 34 100 34 minecraft:gold_block");
        requireAirEverywhere(OTHER_POT_TARGET, potOverworld, publicWorld, otherPot,
            "/execute in accepted another pot's physical dimension key");

        boolean whitelistBefore = server.getPlayerList().isUsingWhitelist();
        run(server, operator, whitelistBefore ? "whitelist off" : "whitelist on");
        boolean whitelistAfter = server.getPlayerList().isUsingWhitelist();
        if (whitelistAfter != whitelistBefore) {
            // Restore the global state before reporting the sandbox escape.
            run(server, server.createCommandSourceStack(), whitelistBefore ? "whitelist on" : "whitelist off");
            throw new AssertionError("A global /whitelist command ran from inside a pot");
        }

    }

    private static void verifyOutsideAndConsoleKeepVanillaScope(MinecraftServer server, Fixture fixture) {
        ServerPlayer operator = fixture.operator;
        ServerLevel potOverworld = fixture.ownerPot.get(SereniteaPotDimension.OVERWORLD);

        operator.setServerLevel(server.overworld());
        operator.snapTo(0.5, 100.0, 0.5);
        // Positive controls keep the earlier literal-name/UUID assertions from passing
        // merely because their syntax stopped parsing.
        run(server, server.createCommandSourceStack(),
            "effect give " + fixture.outside.getUUID() + " minecraft:glowing 5 0 true");
        require(fixture.outside.hasEffect(MobEffects.GLOWING),
            "The literal UUID positive control did not resolve through Vanilla");
        fixture.outside.removeEffect(MobEffects.GLOWING);
        run(server, server.createCommandSourceStack(),
            "effect give " + fixture.outside.getGameProfile().name()
                + " minecraft:glowing 5 0 true");
        require(fixture.outside.hasEffect(MobEffects.GLOWING),
            "The literal name positive control did not resolve through Vanilla");
        fixture.outside.removeEffect(MobEffects.GLOWING);

        clearAt(OTHER_POT_TARGET, fixture.otherPot.get(SereniteaPotDimension.OVERWORLD));
        run(server, server.createCommandSourceStack(),
            "execute in "
                + fixture.otherPot.get(SereniteaPotDimension.OVERWORLD).dimension().identifier()
                + " run setblock 34 100 34 minecraft:gold_block");
        requireBlock(
            fixture.otherPot.get(SereniteaPotDimension.OVERWORLD),
            OTHER_POT_TARGET,
            Blocks.GOLD_BLOCK,
            "The foreign physical-dimension positive control did not execute"
        );
        fixture.outside.addTag("sereniteapot_public_op_target");
        fixture.outside.removeEffect(MobEffects.GLOWING);
        run(server, operator,
            "effect give @a[tag=sereniteapot_public_op_target] minecraft:glowing 5 0 true");
        require(fixture.outside.hasEffect(MobEffects.GLOWING),
            "Leaving a pot changed a real operator's Vanilla selector behavior");
        fixture.outside.removeTag("sereniteapot_public_op_target");
        fixture.outside.removeEffect(MobEffects.GLOWING);

        fixture.otherOwner.setServerLevel(fixture.otherPot.get(SereniteaPotDimension.NETHER));
        fixture.otherOwner.addTag("sereniteapot_console_target");
        fixture.otherOwner.removeEffect(MobEffects.GLOWING);
        run(server, server.createCommandSourceStack(),
            "execute in "
                + fixture.otherPot.get(SereniteaPotDimension.NETHER).dimension().identifier()
                + " run effect give @a[tag=sereniteapot_console_target] minecraft:glowing 5 0 true");
        require(fixture.otherOwner.hasEffect(MobEffects.GLOWING),
            "The pot sandbox changed the server console's Vanilla command scope");
        fixture.otherOwner.removeTag("sereniteapot_console_target");
        fixture.otherOwner.removeEffect(MobEffects.GLOWING);
    }

    private static void verifyLeaveRemainsAvailable(MinecraftServer server, Fixture fixture) {
        fixture.owner.setServerLevel(fixture.ownerPot.get(SereniteaPotDimension.OVERWORLD));
        var potCommand = server.getCommands().getDispatcher().getRoot().getChild("sereniteapot");
        require(potCommand != null
                && potCommand.getChild("leave") != null
                && potCommand.getChild("leave").canUse(fixture.owner.createCommandSourceStack()),
            "/sereniteapot leave was blocked by the pot command sandbox");
    }

    private static void verifyKillSelectorsAreFiltered(MinecraftServer server, Fixture fixture) {
        ServerLevel pot = fixture.ownerPot.get(SereniteaPotDimension.OVERWORLD);
        fixture.owner.setServerLevel(pot);
        fixture.killAllPlayer.setServerLevel(pot);
        fixture.killEntityPlayer.setServerLevel(pot);
        fixture.outside.setServerLevel(server.overworld());

        fixture.killAllPlayer.addTag(KILL_ALL_TARGET);
        fixture.outside.addTag(KILL_ALL_TARGET);
        require(runForResult(
                server,
                fixture.owner,
                "kill @a[tag=" + KILL_ALL_TARGET + "]") == 1,
            "/kill @a did not affect exactly its one legal in-pot target");
        require(!fixture.outside.isDeadOrDying(),
            "/kill @a affected a public-world target");

        fixture.killEntityPlayer.addTag(KILL_ENTITY_TARGET);
        fixture.outside.addTag(KILL_ENTITY_TARGET);
        require(runForResult(
                server,
                fixture.owner,
                "kill @e[type=minecraft:player,tag=" + KILL_ENTITY_TARGET + "]") == 1,
            "/kill @e did not affect exactly its one legal in-pot target");
        require(!fixture.outside.isDeadOrDying(),
            "/kill @e affected a public-world target");

        fixture.outside.removeTag(KILL_ALL_TARGET);
        fixture.outside.removeTag(KILL_ENTITY_TARGET);
    }

    private static void run(MinecraftServer server, ServerPlayer player, String command) {
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static int runForResult(MinecraftServer server, ServerPlayer player, String command) {
        AtomicBoolean completed = new AtomicBoolean();
        AtomicInteger result = new AtomicInteger();
        server.getCommands().performPrefixedCommand(
            player.createCommandSourceStack().withCallback((success, value) -> {
                completed.set(true);
                result.set(success ? value : 0);
            }),
            command
        );
        require(completed.get(), "Command produced no result: /" + command);
        return result.get();
    }

    private static void run(MinecraftServer server, net.minecraft.commands.CommandSourceStack source, String command) {
        server.getCommands().performPrefixedCommand(source, command);
    }

    private static void clearAt(BlockPos position, ServerLevel... levels) {
        for (ServerLevel level : levels) {
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
    }

    private static void requireAirEverywhere(
        BlockPos position,
        ServerLevel pot,
        ServerLevel publicWorld,
        ServerLevel foreignPot,
        String message
    ) {
        requireBlock(pot, position, Blocks.AIR, message + " (current pot changed)");
        requireBlock(publicWorld, position, Blocks.AIR, message + " (public world changed)");
        requireBlock(foreignPot, position, Blocks.AIR, message + " (foreign pot changed)");
    }

    private static void requireBlock(
        ServerLevel level,
        BlockPos position,
        net.minecraft.world.level.block.Block expected,
        String message
    ) {
        if (!level.getBlockState(position).is(expected)) {
            throw new AssertionError(
                message + "; found " + level.getBlockState(position) + " in " + level.dimension().identifier()
            );
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        private final MinecraftServer server;
        private final ServerPlayer outside;
        private final ServerPlayer owner;
        private final ServerPlayer operator;
        private final ServerPlayer otherOwner;
        private final ServerPlayer killAllPlayer;
        private final ServerPlayer killEntityPlayer;
        private final SereniteaPotBundle ownerPot;
        private final SereniteaPotBundle otherPot;
        private final List<ServerPlayer> players;

        private Fixture(
            MinecraftServer server,
            ServerPlayer outside,
            ServerPlayer owner,
            ServerPlayer operator,
            ServerPlayer otherOwner,
            ServerPlayer killAllPlayer,
            ServerPlayer killEntityPlayer,
            SereniteaPotBundle ownerPot,
            SereniteaPotBundle otherPot
        ) {
            this.server = server;
            this.outside = outside;
            this.owner = owner;
            this.operator = operator;
            this.otherOwner = otherOwner;
            this.killAllPlayer = killAllPlayer;
            this.killEntityPlayer = killEntityPlayer;
            this.ownerPot = ownerPot;
            this.otherPot = otherPot;
            this.players = List.of(
                outside, owner, operator, otherOwner, killAllPlayer, killEntityPlayer);
        }

        @SuppressWarnings("removal")
        private static Fixture create(GameTestHelper helper) {
            MinecraftServer server = helper.getLevel().getServer();
            // The outside player is intentionally first for the literal-name escape test.
            ServerPlayer outside = helper.makeMockServerPlayerInLevel();
            ServerPlayer owner = helper.makeMockServerPlayerInLevel();
            ServerPlayer operator = helper.makeMockServerPlayerInLevel();
            ServerPlayer otherOwner = helper.makeMockServerPlayerInLevel();
            ServerPlayer killAllPlayer = helper.makeMockServerPlayerInLevel();
            ServerPlayer killEntityPlayer = helper.makeMockServerPlayerInLevel();

            SereniteaPotBundle ownerPot = createPot(server, owner.getUUID(), 1L);
            SereniteaPotBundle otherPot = createPot(server, otherOwner.getUUID(), 1L);
            return new Fixture(
                server,
                outside,
                owner,
                operator,
                otherOwner,
                killAllPlayer,
                killEntityPlayer,
                ownerPot,
                otherPot
            );
        }

        private static SereniteaPotBundle createPot(MinecraftServer server, UUID owner, long generation) {
            SereniteaPotBundle bundle = SereniteaPotManager.createStaging(owner, generation, 1L);
            GameTestStorage.commitGeneration(
                bundle,
                Map.of(
                    SereniteaPotDimension.OVERWORLD,
                    new SereniteaPotSlotRecord("minecraft:overworld", 0, 100, 0, 0)
                ),
                0
            );
            return bundle;
        }

        @Override
        public void close() {
            List<Throwable> failures = new ArrayList<>();
            server.getPlayerList().deop(new NameAndId(operator.getGameProfile()));
            for (UUID playerId : players.stream().map(ServerPlayer::getUUID).distinct().toList()) {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) {
                        continue;
                    }
                    player.setServerLevel(server.overworld());
                    server.getPlayerList().remove(player);
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            }
            delete(owner.getUUID(), failures);
            delete(otherOwner.getUUID(), failures);
            SereniteaPotManager.catalog().getPlayers().remove(owner.getUUID());
            SereniteaPotManager.catalog().getPlayers().remove(otherOwner.getUUID());
            SereniteaPotManager.saveCatalog();
            if (!failures.isEmpty()) {
                IllegalStateException combined = new IllegalStateException("Command scope fixture cleanup failed");
                failures.forEach(combined::addSuppressed);
                throw combined;
            }
        }

        private void delete(UUID owner, List<Throwable> failures) {
            try {
                SereniteaPotDeletionService.Result result =
                    GameTestStorage.deleteAndReset(server, owner);
                if (result != SereniteaPotDeletionService.Success.INSTANCE) {
                    failures.add(new IllegalStateException("Could not delete test pot " + owner + ": " + result));
                }
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        }
    }
}
