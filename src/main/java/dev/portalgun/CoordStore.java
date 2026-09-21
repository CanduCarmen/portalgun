package dev.portalgun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Список сохранённых координат (config/portalgun/coords.json) + импорт из текстового файла. */
public final class CoordStore {
	public static final class Coord {
		public String name = "";
		public double x;
		public double y;
		public double z;
		/** Где сохранена точка: WORLD (имя папки мира) / SERVER (ip). Пусто — место не запомнено (старые точки). */
		public String kind = "";
		public String value = "";
		public String label = "";

		public Coord() {
		}

		public Coord(String name, double x, double y, double z) {
			this.name = name;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		public boolean hasPlace() {
			return ("WORLD".equals(kind) || "SERVER".equals(kind)) && value != null && !value.isEmpty();
		}

		/** Запоминает место (мир или сервер), где сохранена точка. */
		public Coord at(Travel.Place p) {
			if (p != null && (p.kind == Travel.Kind.WORLD || p.kind == Travel.Kind.SERVER)) {
				kind = p.kind.name();
				value = p.value;
				label = p.label;
			}
			return this;
		}

		public Travel.Place place() {
			return new Travel.Place(Travel.Kind.valueOf(kind), value, label == null || label.isEmpty() ? value : label);
		}

		public String pretty() {
			String s = String.format(java.util.Locale.ROOT, "%s (%.0f, %.0f, %.0f)", name, x, y, z);
			if (hasPlace()) {
				s += "  [" + ("WORLD".equals(kind) ? "мир " : "") + (label == null || label.isEmpty() ? value : label) + "]";
			}
			return s;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static List<Coord> list = new ArrayList<>();

	private CoordStore() {
	}

	public static List<Coord> all() {
		return list;
	}

	/** Выбранная точка или null. */
	public static Coord selected() {
		int i = Settings.get().selectedCoord;
		return (i >= 0 && i < list.size()) ? list.get(i) : null;
	}

	public static void add(Coord c) {
		list.add(c);
		Settings.get().selectedCoord = list.size() - 1;
		save();
		Settings.save();
	}

	public static void removeSelected() {
		int i = Settings.get().selectedCoord;
		if (i >= 0 && i < list.size()) {
			list.remove(i);
			Settings.get().selectedCoord = Math.min(i, list.size() - 1);
			save();
			Settings.save();
		}
	}

	public static void load() {
		Path file = Settings.dir().resolve("coords.json");
		try {
			if (Files.exists(file)) {
				Coord[] arr = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Coord[].class);
				list = new ArrayList<>();
				if (arr != null) {
					for (Coord c : arr) {
						list.add(c);
					}
				}
			}
		} catch (Exception e) {
			PortalGunClient.LOGGER.warn("Could not read coords.json", e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(Settings.dir());
			Files.writeString(Settings.dir().resolve("coords.json"), GSON.toJson(list), StandardCharsets.UTF_8);
		} catch (IOException e) {
			PortalGunClient.LOGGER.warn("Could not save coords.json", e);
		}
	}

	/**
	 * Импорт из текстового файла. Поддерживаемые строки (по одной точке в строке):
	 * "x y z", "x,y,z", "name x y z", "x y z name". Строки с # — комментарии.
	 * Возвращает число добавленных точек.
	 */
	public static int importFile(Path file) {
		int count = 0;
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				Coord c = parseLine(line, list.size() + count + 1);
				if (c != null) {
					c.at(Travel.here(net.minecraft.client.Minecraft.getInstance()));
					list.add(c);
					count++;
				}
			}
		} catch (Exception e) {
			Compat.msg("Ошибка импорта: " + e.getMessage());
			return 0;
		}
		if (count > 0) {
			save();
		}
		return count;
	}

	static Coord parseLine(String line, int index) {
		String s = line.trim();
		if (s.isEmpty() || s.startsWith("#")) {
			return null;
		}
		String[] t = s.split("[\\s,;]+");
		int n = t.length;
		if (n >= 3 && isNum(t[0]) && isNum(t[1]) && isNum(t[2])) {
			String name = n > 3 ? String.join(" ", java.util.Arrays.copyOfRange(t, 3, n)) : "Point " + index;
			return new Coord(name, Double.parseDouble(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2]));
		}
		if (n >= 3 && isNum(t[n - 3]) && isNum(t[n - 2]) && isNum(t[n - 1])) {
			String name = n > 3 ? String.join(" ", java.util.Arrays.copyOfRange(t, 0, n - 3)) : "Point " + index;
			return new Coord(name, Double.parseDouble(t[n - 3]), Double.parseDouble(t[n - 2]), Double.parseDouble(t[n - 1]));
		}
		return null;
	}

	private static boolean isNum(String s) {
		try {
			Double.parseDouble(s);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
