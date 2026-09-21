package dev.portalgun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Список серверов для «случайного портала»: config/portalgun/servers.txt.
 * Одна строка = один сервер: "ip[:порт]" или "ip[:порт] Красивое название". Строки с # — комментарии.
 */
public final class RandomServers {
	public record Entry(String ip, String name) {
	}

	private static final String TEMPLATE = """
			# Список серверов для кнопки "Случайный сервер" в меню пушки.
			# По одному серверу в строке:  ip[:порт] [название]
			# Примеры (раскомментируй и замени на свои):
			# play.example.com
			# mc.example.org:25566 Мой любимый сервер
			""";

	private RandomServers() {
	}

	public static Path file() {
		return Settings.dir().resolve("servers.txt");
	}

	/** Создаёт файл-шаблон, если его ещё нет. */
	public static void ensureFile() {
		Path f = file();
		try {
			if (!Files.exists(f)) {
				Files.createDirectories(f.getParent());
				Files.writeString(f, TEMPLATE, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			PortalGunClient.LOGGER.warn("Could not create servers.txt", e);
		}
	}

	public static List<Entry> load() {
		ensureFile();
		List<Entry> result = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
				String s = line.trim();
				if (s.isEmpty() || s.startsWith("#")) {
					continue;
				}
				String[] t = s.split("\\s+", 2);
				String name = t.length > 1 && !t[1].isBlank() ? t[1].trim() : t[0];
				result.add(new Entry(t[0], name));
			}
		} catch (IOException e) {
			PortalGunClient.LOGGER.warn("Could not read servers.txt", e);
		}
		return result;
	}

	/** Случайный сервер из файла; сервер, на котором игрок уже находится, по возможности не выбирается. */
	public static Entry pick(String excludeIp) {
		List<Entry> all = load();
		if (all.isEmpty()) {
			return null;
		}
		List<Entry> pool = new ArrayList<>();
		for (Entry e : all) {
			if (!e.ip().equalsIgnoreCase(excludeIp)) {
				pool.add(e);
			}
		}
		if (pool.isEmpty()) {
			pool = all;
		}
		return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
	}
}
