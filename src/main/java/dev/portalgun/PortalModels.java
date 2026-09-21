package dev.portalgun;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Загрузка модели портала (Blockbench .bbmodel).
 *
 * Порядок поиска файла:
 *  1) config/portalgun/portal.bbmodel — если положить сюда свой файл, он подхватится БЕЗ пересборки мода
 *     (перезагрузка в игре: команда /portalgun reload);
 *  2) встроенный assets/portalgun/bb/portal.bbmodel из самого мода.
 * Если не читается ни то, ни другое — порталы рисуются старой заглушкой из частиц.
 *
 * Текстура модели лежит в ресурсах мода: assets/portalgun/textures/model/portal.png
 * (см. {@link PortalRenderer#TEXTURE}); UV в .bbmodel считаются от resolution модели.
 */
public final class PortalModels {
	private static volatile BbModel portal;

	private PortalModels() {
	}

	public static BbModel portal() {
		return portal;
	}

	/** (Пере)загружает модель. Безопасно вызывать сколько угодно раз. */
	public static void load() {
		portal = loadOne("portal");
	}

	private static BbModel loadOne(String name) {
		Path override = Settings.dir().resolve(name + ".bbmodel");
		if (Files.isRegularFile(override)) {
			try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
				BbModel m = BbModel.parse(r);
				PortalGunClient.LOGGER.info("Loaded {} from {}", name, override);
				return m;
			} catch (Exception e) {
				PortalGunClient.LOGGER.warn("Could not read {} — falling back to the built-in model", override, e);
			}
		}
		String res = "/assets/" + PortalGunClient.MOD_ID + "/bb/" + name + ".bbmodel";
		try (InputStream in = PortalModels.class.getResourceAsStream(res)) {
			if (in == null) {
				PortalGunClient.LOGGER.warn("Built-in model {} not found", res);
				return null;
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return BbModel.parse(r);
			}
		} catch (IOException | RuntimeException e) {
			PortalGunClient.LOGGER.warn("Could not parse built-in model {}", res, e);
			return null;
		}
	}

	/** Длительность анимации в тиках; если анимации нет или её длина 0 — значение по умолчанию. */
	public static int animTicks(String animation, int defaultTicks) {
		BbModel m = portal;
		if (m == null) {
			return defaultTicks;
		}
		double len = m.length(animation);
		return len > 0.0 ? Math.max(1, (int) Math.round(len * 20.0)) : defaultTicks;
	}
}
