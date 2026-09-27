package org.edtp.sereniteapot.command;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import org.edtp.sereniteapot.i18n.MessageKey;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

public final class PersistenceFeedbackGameTest {
    @GameTest
    public void feedbackWaitsForPersistenceAndReportsFailure(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var failure = new AtomicReference<Throwable>();
        var complete = new CompletableFuture<Void>();
        server.execute(() -> {
            try {
                var messages = new ArrayList<Component>();
                var sink = new CommandSource() {
                    public void sendSystemMessage(Component message) { messages.add(message); }
                    public boolean acceptsSuccess() { return true; }
                    public boolean acceptsFailure() { return true; }
                    public boolean shouldInformAdmins() { return false; }
                };
                var source = server.createCommandSourceStack().withSource(sink);
                var context = server.getCommands().getDispatcher().parse("sereniteapot", source)
                        .getContext().build("sereniteapot");
                var saved = new CompletableFuture<Void>();
                SereniteaPotCommandSupport.saved(context, saved, MessageKey.COMMAND_UNFREEZE_SUCCESS);
                if (!messages.isEmpty()) throw new AssertionError("Success sent before disk completion");
                saved.complete(null);
                if (messages.size() != 1) throw new AssertionError("Missing durable success feedback");
                messages.clear();
                var failed = new CompletableFuture<Void>();
                SereniteaPotCommandSupport.saved(context, failed, MessageKey.COMMAND_UNFREEZE_SUCCESS);
                failed.completeExceptionally(new java.io.IOException("test disk failure"));
                var expected = component(source, message(MessageKey.COMMAND_SAVE_FAILED)).getString();
                if (messages.size() != 1 || !messages.getFirst().getString().equals(expected)) {
                    throw new AssertionError("Failed save was reported as successful");
                }
            } catch (Throwable error) { failure.set(error); }
            finally { complete.complete(null); }
        });
        helper.onEachTick(() -> {
            if (!complete.isDone()) return;
            if (failure.get() != null) helper.fail("Persistence feedback: " + failure.get());
            else helper.succeed();
        });
    }
}
