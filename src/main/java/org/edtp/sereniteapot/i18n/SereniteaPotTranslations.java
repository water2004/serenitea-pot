package org.edtp.sereniteapot.i18n;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.SereniteaPotMod;

import java.io.IOException;
import java.io.InputStream;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 服务端-only 本地化：根据客户端上报的语言生成最终 literal 文本。
 *
 * <p>纯净客户端没有本 Mod 的语言表，直接发送自定义 translatable key 只会显示键名。
 * 因此语言 JSON 保留在服务端，由这里使用 Mojang 的解析器加载并在发包前完成翻译。</p>
 */
public final class SereniteaPotTranslations {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final Map<String, Map<String, String>> LANGUAGES = Map.of(
        DEFAULT_LANGUAGE, load(DEFAULT_LANGUAGE),
        "zh_cn", load("zh_cn")
    );

    static {
        Set<String> requiredKeys = LANGUAGES.get(DEFAULT_LANGUAGE).keySet();
        for (Map.Entry<String, Map<String, String>> language : LANGUAGES.entrySet()) {
            if (!language.getValue().keySet().equals(requiredKeys)) {
                throw new IllegalStateException(
                    "Translation keys for " + language.getKey() + " do not match " + DEFAULT_LANGUAGE
                );
            }
        }
    }

    private SereniteaPotTranslations() {
    }

    public static Message message(String key, Object... arguments) {
        if (!LANGUAGES.get(DEFAULT_LANGUAGE).containsKey(key)) {
            throw new IllegalArgumentException("Unknown Serenitea Pot translation key " + key);
        }
        return new Message(key, arguments);
    }

    public static MutableComponent component(CommandSourceStack source, Message message) {
        ServerPlayer player = source.getPlayer();
        return component(player == null ? DEFAULT_LANGUAGE : player.clientInformation().language(), message);
    }

    public static MutableComponent component(ServerPlayer player, Message message) {
        return component(player.clientInformation().language(), message);
    }

    public static String fallback(Message message) {
        return translate(DEFAULT_LANGUAGE, message);
    }

    private static MutableComponent component(String language, Message message) {
        return Component.literal(translate(language, message));
    }

    public static String translate(String requestedLanguage, Message message) {
        String language = requestedLanguage.toLowerCase(Locale.ROOT);
        Map<String, String> translations = LANGUAGES.getOrDefault(language, LANGUAGES.get(DEFAULT_LANGUAGE));
        String pattern = translations.get(message.key());
        if (pattern == null) {
            pattern = LANGUAGES.get(DEFAULT_LANGUAGE).getOrDefault(message.key(), message.key());
        }
        Object[] arguments = message.arguments().clone();
        for (int index = 0; index < arguments.length; index++) {
            if (arguments[index] instanceof Message nested) {
                arguments[index] = translate(language, nested);
            }
        }
        try {
            return String.format(Locale.ROOT, pattern, arguments);
        } catch (IllegalFormatException error) {
            SereniteaPotMod.LOGGER.error("Invalid translation format for {}", message.key(), error);
            return pattern;
        }
    }

    private static Map<String, String> load(String language) {
        String path = "/assets/" + SereniteaPotMod.MOD_ID + "/lang/" + language + ".json";
        try (InputStream input = SereniteaPotTranslations.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing server translation " + path);
            }
            Map<String, String> translations = new LinkedHashMap<>();
            Language.loadFromJson(input, translations::put);
            return Map.copyOf(translations);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load server translation " + path, error);
        }
    }

    public record Message(String key, Object... arguments) {
        public Message {
            Objects.requireNonNull(key, "key");
            arguments = arguments.clone();
        }

        @Override
        public Object[] arguments() {
            return arguments.clone();
        }
    }
}
