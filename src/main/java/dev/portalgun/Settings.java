package dev.portalgun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки мода (config/portalgun/settings.json). */
public final class Settings {
	/** Куда стреляет пушка. kind: CURRENT | WORLD | SERVER. */
	public static final class Dest {
		public String kind = "CURRENT";
		/** WORLD: имя папки сохранения; SERVER: ip[:port]. */
		public String value = "";
		public String label = "Current world";
	}

	/** Переносить вещи с сервера в локальный мир. */
	public boolean carryServerItems = false;
	/** При запуске игры сразу заходить в выбранный мир/сервер вместо главного меню. */
	public boolean autoJoinOnLaunch = false;
	/** ID предмета, который работает как пушка (напр. minecraft:stick). Пусто — только выданная командой палка с моделью пушки. */
	public String gunItem = "";
	/** Делиться своими порталами через релей и видеть порталы других игроков. */
	public boolean shareEnabled = false;
	/** Релей по умолчанию (используется, пока стоит галочка «по умолчанию»). */
	public static final String DEFAULT_RELAY_URL = "http://204.77.3.92:8765";
	public static final String DEFAULT_RELAY_TOKEN = "svo";
	/** true — брать релей по умолчанию; false — свои адрес и токен ниже. */
	public boolean useDefaultRelay = true;
	/** Свой адрес релея, напр. http://1.2.3.4:8765 (работает при выключенной галочке «по умолчанию»). */
	public String relayUrl = "";
	/** Свой токен релея (пусто — без токена). */
	public String relayToken = "";
	/** Порталы не исчезают по времени (пока не выйдешь из мира/сервера или не войдёшь в них). */
	public boolean unlimitedTime = true;
	/** Сколько секунд живёт портал (если unlimitedTime выключено). Без верхнего предела. */
	public int portalSeconds = 30;
	/** Количество своих порталов не ограничено. */
	public boolean unlimitedPortals = true;
	/** Сколько своих порталов одновременно (если unlimitedPortals выключено; старейший исчезает). Без верхнего предела. */
	public int maxPortals = 1;
	/** true — цвет по типу (синий: мир→мир, зелёный: мир→сервер); false — свой цвет portalHue. */
	public boolean autoColor = true;
	/** Свой цвет портала: оттенок 0..1 (полоска в меню). */
	public float portalHue = 0.55f;
	public int selectedCoord = -1;
	public Dest dest = new Dest();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Settings instance = new Settings();

	public static Settings get() {
		return instance;
	}

	/** Адрес релея с учётом галочки «по умолчанию». */
	public String effectiveRelayUrl() {
		String u = useDefaultRelay ? DEFAULT_RELAY_URL : relayUrl;
		return u == null ? "" : u.trim();
	}

	/** Токен релея с учётом галочки «по умолчанию». */
	public String effectiveRelayToken() {
		String t = useDefaultRelay ? DEFAULT_RELAY_TOKEN : relayToken;
		return t == null ? "" : t.trim();
	}

	/** Приводит значения из файла к допустимым пределам. */
	private void sanitize() {
		portalSeconds = Math.max(1, portalSeconds);
		maxPortals = Math.max(1, maxPortals);
		if (!(portalHue >= 0.0f && portalHue <= 1.0f)) {
			portalHue = 0.55f;
		}
		if (dest == null) {
			dest = new Dest();
		}
	}

	static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("portalgun");
	}

	public static void load() {
		Path file = dir().resolve("settings.json");
		try {
			if (Files.exists(file)) {
				String raw = Files.readString(file, StandardCharsets.UTF_8);
				Settings s = GSON.fromJson(raw, Settings.class);
				if (s != null) {
					// старый файл со своим релеем (до появления галочки «по умолчанию») — оставляем свой
					if (!raw.contains("useDefaultRelay") && s.relayUrl != null && !s.relayUrl.isBlank()) {
						s.useDefaultRelay = false;
					}
					s.sanitize();
					instance = s;
				}
			}
		} catch (Exception e) {
			PortalGunClient.LOGGER.warn("Could not read settings.json, using defaults", e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(dir());
			Files.writeString(dir().resolve("settings.json"), GSON.toJson(instance), StandardCharsets.UTF_8);
		} catch (IOException e) {
			PortalGunClient.LOGGER.warn("Could not save settings.json", e);
		}
	}
}
