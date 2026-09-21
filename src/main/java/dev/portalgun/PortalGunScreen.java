package dev.portalgun;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Маленькое меню пушки: вкладки Координаты / Миры / Серверы / Настройки.
 * Фон меню — PNG: assets/portalgun/textures/gui/portal_gun_menu.png (рисуй свой, размер = PANEL_W x PANEL_H).
 * Схема, где лежат кнопки и поля: docs/gui_layout.png в корне проекта.
 */
public final class PortalGunScreen extends Screen {
	private static final int W = 300;
	private static final int ROWS = 5;
	private static final int ROW_H = 22;

	/** PNG фона меню. */
	private static final Identifier PANEL = Identifier.fromNamespaceAndPath(PortalGunClient.MOD_ID, "textures/gui/portal_gun_menu.png");
	/** Размер PNG в пикселях (1 пиксель PNG = 1 единица GUI). Если нарисуешь PNG другого размера — поменяй тут. */
	private static final int PANEL_W = 320;
	private static final int PANEL_H = 272;
	/** Смещение панели относительно области виджетов: слева/справа по 10, сверху 8 (см. left()/top()). */
	private static final int PAD_X = (PANEL_W - W) / 2;
	private static final int PAD_TOP = 8;
	private static final int LABEL_COLOR = 0xFFC8FFB4; // ARGB

	/** Подписи, которые рисуются текстом прямо на PNG (а не серыми кнопками). */
	private record Label(String text, int x, int y, int w) {
	}

	private final List<Label> textLabels = new ArrayList<>();
	/** Цветные прямоугольники поверх фона: x, y, w, h, ARGB (образец цвета портала). */
	private final List<int[]> rects = new ArrayList<>();

	private int tab = 0;
	private int page = 0;

	// черновики полей — чтобы текст не пропадал при перерисовке вкладки
	private String draftName = "";
	private String draftX = "";
	private String draftY = "";
	private String draftZ = "";
	private String draftFile = "";
	private String draftIp = "";

	public PortalGunScreen() {
		super(Component.literal("Портальная пушка"));
	}

	// ------------------------------------------------------------------ построение

	private int left() {
		return this.width / 2 - W / 2;
	}

	private int top() {
		return Math.max(8, this.height / 2 - 120);
	}

	/** Фон меню: сначала ванильный фон, поверх него PNG. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractBackground(graphics, mouseX, mouseY, delta);
		graphics.blit(RenderPipelines.GUI_TEXTURED, PANEL, left() - PAD_X, top() - PAD_TOP,
				0.0F, 0.0F, PANEL_W, PANEL_H, PANEL_W, PANEL_H);
	}

	/** Виджеты рисует super, подписи — поверх них. */
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		for (int[] r : rects) {
			graphics.fill(r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1] + r[3] + 1, 0xFF000000);
			graphics.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], r[4]);
		}
		for (Label l : textLabels) {
			String t = font().plainSubstrByWidth(l.text(), l.w()); // обрезаем, чтобы не вылезало за панель
			int tx = l.x() + Math.max(0, (l.w() - font().width(t)) / 2);
			graphics.text(font(), t, tx, l.y() + 6, LABEL_COLOR, true);
		}
	}

	@Override
	public void removed() {
		Settings.save();
		super.removed();
	}

	@Override
	protected void init() {
		textLabels.clear();
		rects.clear();
		int left = left();
		int top = top();

		String[] names = {"Коорд.", "Миры", "Серв.", "Опции", "Портал", "Сеть"};
		int tw = W / 6;
		for (int i = 0; i < 6; i++) {
			final int idx = i;
			Button b = button(names[i], left + i * tw, top, tw - 2, () -> {
				tab = idx;
				page = 0;
				rebuildWidgets();
			});
			b.active = tab != i;
		}

		int y = top + 26;
		switch (tab) {
			case 0:
				buildCoords(left, y);
				break;
			case 1:
				buildWorlds(left, y);
				break;
			case 2:
				buildServers(left, y);
				break;
			case 3:
				buildSettings(left, y);
				break;
			case 4:
				buildPortal(left, y);
				break;
			default:
				buildNet(left, y);
				break;
		}
	}

	private Font font() {
		return Minecraft.getInstance().font;
	}

	private Button button(String label, int x, int y, int w, Runnable action) {
		Button b = Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, w, 20).build();
		addRenderableWidget(b);
		return b;
	}

	private EditBox editBox(int x, int y, int w, String hint, String value, java.util.function.Consumer<String> onChange) {
		EditBox box = new EditBox(font(), x, y, w, 20, Component.literal(hint));
		box.setMaxLength(256);
		box.setHint(Component.literal(hint));
		box.setValue(value);
		box.setResponder(onChange);
		addRenderableWidget(box);
		return box;
	}

	/** Строка-подпись: рисуется текстом поверх PNG-фона (см. extractRenderState). */
	private void label(String text, int x, int y, int w) {
		textLabels.add(new Label(text, x, y, w));
	}

	/** Список из кнопок-строк с постраничной навигацией. */
	private void list(int left, int y, List<String> labels, IntConsumer onClick) {
		int pages = Math.max(1, (labels.size() + ROWS - 1) / ROWS);
		page = Math.max(0, Math.min(page, pages - 1));
		for (int r = 0; r < ROWS; r++) {
			int idx = page * ROWS + r;
			if (idx >= labels.size()) {
				break;
			}
			final int i = idx;
			button(cut(labels.get(idx)), left, y + r * ROW_H, W, () -> onClick.accept(i));
		}
		int ny = y + ROWS * ROW_H;
		Button prev = button("<", left, ny, 40, () -> {
			page--;
			rebuildWidgets();
		});
		prev.active = page > 0;
		Button next = button(">", left + W - 40, ny, 40, () -> {
			page++;
			rebuildWidgets();
		});
		next.active = page < pages - 1;
		label((page + 1) + " / " + pages, left + 44, ny, W - 88);
	}

	private static String cut(String s) {
		return s.length() > 50 ? s.substring(0, 49) + ".." : s;
	}

	private static String mark(boolean selected, String text) {
		return (selected ? "> " : "  ") + text;
	}

	private String status() {
		CoordStore.Coord c = CoordStore.selected();
		Settings.Dest d = Settings.get().dest;
		return "Точка: " + (c == null ? "-" : c.name) + "  |  Назначение: " + d.label;
	}

	// ------------------------------------------------------------------ вкладка 1: координаты

	private void buildCoords(int left, int y) {
		List<CoordStore.Coord> coords = CoordStore.all();
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < coords.size(); i++) {
			labels.add(mark(i == Settings.get().selectedCoord, coords.get(i).pretty()));
		}
		list(left, y, labels, i -> {
			Settings.get().selectedCoord = i;
			applyCoordPlace(coords.get(i));
			Settings.save();
			rebuildWidgets();
		});

		int fy = y + ROWS * ROW_H + 26;
		editBox(left, fy, 100, "Название", draftName, s -> draftName = s);
		editBox(left + 104, fy, 58, "X", draftX, s -> draftX = s);
		editBox(left + 166, fy, 58, "Y", draftY, s -> draftY = s);
		editBox(left + 228, fy, 58, "Z", draftZ, s -> draftZ = s);

		int by = fy + 24;
		button("Добавить", left, by, 96, this::addCoord);
		button("Моя позиция", left + 102, by, 96, this::fillHere);
		button("Удалить выбранную", left + 204, by, 96, () -> {
			CoordStore.removeSelected();
			rebuildWidgets();
		});

		int iy = by + 24;
		editBox(left, iy, 200, "Путь к .txt (или перетащи файл сюда)", draftFile, s -> draftFile = s);
		button("Импорт файла", left + 204, iy, 96, () -> {
			if (draftFile.isBlank()) {
				Compat.msg("Укажи путь к .txt файлу или перетащи его в окно игры.");
				return;
			}
			int n = CoordStore.importFile(Path.of(draftFile.trim()));
			Compat.msg("Импортировано точек: " + n);
			rebuildWidgets();
		});

		label(status(), left, iy + 24, W);
	}

	/** Точка помнит мир/сервер, где её сохранили: выбираешь точку — назначением становится это место. */
	private void applyCoordPlace(CoordStore.Coord c) {
		if (c != null && c.hasPlace()) {
			setDest(c.kind, c.value, ("WORLD".equals(c.kind) ? "Мир: " : "Сервер: ") + (c.label == null || c.label.isEmpty() ? c.value : c.label));
		}
	}

	private void fillHere() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			return;
		}
		draftX = String.format(Locale.ROOT, "%.1f", p.getX());
		draftY = String.format(Locale.ROOT, "%.1f", p.getY());
		draftZ = String.format(Locale.ROOT, "%.1f", p.getZ());
		rebuildWidgets();
	}

	private void addCoord() {
		try {
			double x = Double.parseDouble(draftX.trim().replace(',', '.'));
			double y = Double.parseDouble(draftY.trim().replace(',', '.'));
			double z = Double.parseDouble(draftZ.trim().replace(',', '.'));
			String name = draftName.isBlank() ? "Point " + (CoordStore.all().size() + 1) : draftName.trim();
			CoordStore.Coord c = new CoordStore.Coord(name, x, y, z).at(Travel.here(Minecraft.getInstance()));
			CoordStore.add(c);
			applyCoordPlace(c);
			draftName = "";
			rebuildWidgets();
		} catch (NumberFormatException e) {
			Compat.msg("X, Y и Z должны быть числами.");
		}
	}

	/** Перетащили .txt на окно игры. */
	@Override
	public void onFilesDrop(List<Path> paths) {
		int n = 0;
		for (Path p : paths) {
			n += CoordStore.importFile(p);
		}
		Compat.msg("Импортировано точек: " + n);
		rebuildWidgets();
	}

	// ------------------------------------------------------------------ вкладка 2: миры

	private void buildWorlds(int left, int y) {
		List<String> worlds = listWorlds();
		Settings.Dest d = Settings.get().dest;
		List<String> labels = new ArrayList<>();
		labels.add(mark("CURRENT".equals(d.kind), "[ Текущий мир — телепорт по координатам ]"));
		for (String w : worlds) {
			labels.add(mark("WORLD".equals(d.kind) && w.equals(d.value), w));
		}
		list(left, y, labels, i -> {
			if (i == 0) {
				setDest("CURRENT", "", "Текущий мир");
			} else {
				String w = worlds.get(i - 1);
				setDest("WORLD", w, "Мир: " + w);
			}
			rebuildWidgets();
		});
		label(status(), left, y + ROWS * ROW_H + 26, W);
	}

	private static List<String> listWorlds() {
		List<String> result = new ArrayList<>();
		Path saves = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("saves");
		if (!Files.isDirectory(saves)) {
			return result;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(saves)) {
			for (Path p : stream) {
				if (Files.isDirectory(p) && Files.exists(p.resolve("level.dat"))) {
					result.add(p.getFileName().toString());
				}
			}
		} catch (IOException e) {
			PortalGunClient.LOGGER.warn("Could not list saves", e);
		}
		Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
		return result;
	}

	// ------------------------------------------------------------------ вкладка 3: серверы

	private void buildServers(int left, int y) {
		ServerList servers = new ServerList(Minecraft.getInstance());
		servers.load();
		Settings.Dest d = Settings.get().dest;

		List<ServerData> entries = new ArrayList<>();
		for (int i = 0; i < servers.size(); i++) {
			entries.add(servers.get(i));
		}
		List<String> labels = new ArrayList<>();
		labels.add(mark("CURRENT".equals(d.kind), "[ Текущий мир — телепорт по координатам ]"));
		for (ServerData s : entries) {
			labels.add(mark("SERVER".equals(d.kind) && s.ip.equals(d.value), s.name + "  (" + s.ip + ")"));
		}
		list(left, y, labels, i -> {
			if (i == 0) {
				setDest("CURRENT", "", "Текущий мир");
			} else {
				ServerData s = entries.get(i - 1);
				setDest("SERVER", s.ip, "Сервер: " + s.name);
			}
			rebuildWidgets();
		});

		int iy = y + ROWS * ROW_H + 26;
		editBox(left, iy, 200, "IP сервера (напр. play.example.com:25565)", draftIp, s -> draftIp = s);
		button("Использовать IP", left + 204, iy, 96, () -> {
			String ip = draftIp.trim();
			if (ip.isEmpty()) {
				Compat.msg("Укажи IP сервера.");
				return;
			}
			setDest("SERVER", ip, "Сервер: " + ip);
			rebuildWidgets();
		});
		RandomServers.ensureFile();
		button("Случайный сервер из servers.txt — открыть портал", left, iy + 24, W, this::randomServerPortal);
		label(status(), left, iy + 48, W);
	}

	/** Берёт случайный сервер из config/portalgun/servers.txt, делает его назначением и открывает портал. */
	private void randomServerPortal() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			return;
		}
		Travel.Place here = Travel.here(Minecraft.getInstance());
		RandomServers.Entry e = RandomServers.pick(here.kind == Travel.Kind.SERVER ? here.value : "");
		if (e == null) {
			Compat.msg("Список серверов пуст. Впиши IP по одному в строке в файл: " + RandomServers.file());
			return;
		}
		setDest("SERVER", e.ip(), "Сервер: " + e.name());
		onClose();
		GunItem.shoot(p, true);
		Compat.msg("Портал на случайный сервер: " + e.name() + " (" + e.ip() + ")");
	}

	private void setDest(String kind, String value, String label) {
		Settings.Dest d = Settings.get().dest;
		d.kind = kind;
		d.value = value;
		d.label = label;
		Settings.save();
	}

	// ------------------------------------------------------------------ вкладка 4: настройки

	private void buildSettings(int left, int y) {
		Settings s = Settings.get();
		Checkbox carry = Checkbox.builder(Component.literal("Брать вещи с сервера в локальный мир"), font())
				.pos(left, y + 4)
				.selected(s.carryServerItems)
				.onValueChange((box, value) -> {
					s.carryServerItems = value;
					Settings.save();
				})
				.build();
		addRenderableWidget(carry);

		Checkbox auto = Checkbox.builder(Component.literal("При запуске игры сразу заходить в выбранный мир/сервер"), font())
				.pos(left, y + 34)
				.selected(s.autoJoinOnLaunch)
				.onValueChange((box, value) -> {
					s.autoJoinOnLaunch = value;
					Settings.save();
				})
				.build();
		addRenderableWidget(auto);

		button("Выдать портальную пушку (нужны читы)", left, y + 70, W, () -> {
			GunItem.give();
			onClose();
		});
		button(GunItem.gunless() ? "Режим без пушки: ВКЛ (нажми — выключить)" : "Режим без пушки: ВЫКЛ (нажми — включить)",
				left, y + 96, W, () -> {
					GunItem.toggleGunless();
					if (GunItem.gunless()) {
						onClose(); // закрываем меню — можно сразу стрелять
					} else {
						rebuildWidgets();
					}
				});
		// предмет, который работает как пушка (как «инструмент» в Litematica)
		editBox(left, y + 124, 176, "ID предмета-пушки, напр. minecraft:stick", s.gunItem == null ? "" : s.gunItem, v -> {
			if (v.isBlank()) {
				s.gunItem = "";
				Settings.save();
			} else {
				String id = GunItem.normalizeItemId(v);
				if (id != null) {
					s.gunItem = id;
					Settings.save();
				}
			}
		});
		button("Из руки", left + 178, y + 124, 62, () -> {
			LocalPlayer p = Minecraft.getInstance().player;
			if (p == null || p.getMainHandItem().isEmpty()) {
				Compat.msg("Возьми нужный предмет в руку и нажми снова.");
				return;
			}
			s.gunItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString();
			Settings.save();
			rebuildWidgets();
		});
		button("Сброс", left + 242, y + 124, 58, () -> {
			s.gunItem = "";
			Settings.save();
			rebuildWidgets();
		});
		label("Предмет-пушка: " + (s.gunItem == null || s.gunItem.isBlank() ? "только палка из /portalgun give" : s.gunItem), left, y + 150, W);
		label("Выстрел: ПКМ. Меню: Shift+ПКМ или /portalgun", left, y + 172, W);
		label("Без пушки: клавиша G (меняется в Управлении).", left, y + 194, W);
	}

	// ------------------------------------------------------------------ вкладка 5: портал

	private void buildPortal(int left, int y) {
		Settings s = Settings.get();
		addRenderableWidget(Checkbox.builder(Component.literal("Время жизни портала: без ограничения"), font())
				.pos(left, y)
				.selected(s.unlimitedTime)
				.onValueChange((box, value) -> {
					s.unlimitedTime = value;
					Settings.save();
					rebuildWidgets();
				})
				.build());
		numberBox(left, y + 22, "Секунд жизни портала (любое число)", s.portalSeconds, !s.unlimitedTime, v -> s.portalSeconds = v);
		addRenderableWidget(Checkbox.builder(Component.literal("Количество порталов: без ограничения"), font())
				.pos(left, y + 48)
				.selected(s.unlimitedPortals)
				.onValueChange((box, value) -> {
					s.unlimitedPortals = value;
					Settings.save();
					rebuildWidgets();
				})
				.build());
		numberBox(left, y + 70, "Сколько порталов одновременно (любое число)", s.maxPortals, !s.unlimitedPortals, v -> s.maxPortals = v);
		addRenderableWidget(Checkbox.builder(Component.literal("Авто-цвет по типу портала"), font())
				.pos(left, y + 98)
				.selected(s.autoColor)
				.onValueChange((box, value) -> {
					s.autoColor = value;
					Settings.save();
					rebuildWidgets();
				})
				.build());
		label("Синий — мир → мир, зелёный — мир → сервер", left, y + 116, W);
		label(s.autoColor ? "Свой цвет (включится без авто-цвета):" : "Свой цвет портала:", left, y + 136, W);
		HueSlider hue = new HueSlider(left, y + 158, W - 30, s.portalHue, v -> s.portalHue = v);
		hue.active = !s.autoColor;
		addRenderableWidget(hue);
		int[] c = PortalRenderer.hsv(s.portalHue);
		rects.add(new int[] {left + W - 22, y + 158, 22, 20, s.autoColor ? 0xFF555555 : (0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2])});
		button("Сбросить (всё без ограничений, авто-цвет)", left, y + 186, W, () -> {
			s.unlimitedTime = true;
			s.unlimitedPortals = true;
			s.portalSeconds = 30;
			s.maxPortals = 1;
			s.autoColor = true;
			s.portalHue = 0.55f;
			Settings.save();
			rebuildWidgets();
		});
	}

	/** Поле для целого числа >= 1 без верхнего предела. */
	private void numberBox(int x, int y, String hint, int value, boolean enabled, IntConsumer onChange) {
		EditBox box = editBox(x, y, W, hint, String.valueOf(value), t -> {
			try {
				int v = Integer.parseInt(t.trim());
				if (v >= 1) {
					onChange.accept(v);
					Settings.save();
				}
			} catch (NumberFormatException ignored) {
				// пустое или неполное значение — не меняем
			}
		});
		box.setMaxLength(9);
		box.setEditable(enabled);
	}

	/** Полоска выбора цвета (оттенок 0..1). */
	private static final class HueSlider extends AbstractSliderButton {
		private final java.util.function.Consumer<Float> onChange;

		HueSlider(int x, int y, int w, float hue, java.util.function.Consumer<Float> onChange) {
			super(x, y, w, 20, Component.empty(), hue);
			this.onChange = onChange;
		}

		@Override
		protected void updateMessage() {
		}

		@Override
		protected void applyValue() {
			onChange.accept((float) this.value);
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			Settings.save();
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
			int x = getX();
			int y = getY();
			int w = getWidth();
			int h = getHeight();
			for (int i = 0; i < w; i++) {
				int[] c = PortalRenderer.hsv(i / (float) Math.max(1, w - 1));
				g.fill(x + i, y, x + i + 1, y + h, 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2]);
			}
			if (!this.active) {
				g.fill(x, y, x + w, y + h, 0xAA000000);
				return;
			}
			int hx = x + 4 + (int) Math.round(this.value * (w - 8));
			g.fill(hx - 2, y - 1, hx + 2, y + h + 1, 0xFF000000);
			g.fill(hx - 1, y, hx + 1, y + h, 0xFFFFFFFF);
		}
	}

	// ------------------------------------------------------------------ вкладка 6: сеть

	private void buildNet(int left, int y) {
		Settings s = Settings.get();
		addRenderableWidget(Checkbox.builder(Component.literal("Делиться порталами и видеть чужие (нужен релей)"), font())
				.pos(left, y + 4)
				.selected(s.shareEnabled)
				.onValueChange((box, value) -> {
					s.shareEnabled = value;
					Settings.save();
				})
				.build());
		addRenderableWidget(Checkbox.builder(Component.literal("Использовать релей по умолчанию"), font())
				.pos(left, y + 30)
				.selected(s.useDefaultRelay)
				.onValueChange((box, value) -> {
					s.useDefaultRelay = value;
					Settings.save();
					rebuildWidgets();
				})
				.build());
		EditBox url = editBox(left, y + 58, W, "Свой адрес релея, напр. http://1.2.3.4:8765",
				s.useDefaultRelay ? Settings.DEFAULT_RELAY_URL : (s.relayUrl == null ? "" : s.relayUrl), v -> {
					if (!s.useDefaultRelay) {
						s.relayUrl = v.trim();
						Settings.save();
					}
				});
		EditBox token = editBox(left, y + 84, W, "Свой токен (пусто — без токена)",
				s.useDefaultRelay ? Settings.DEFAULT_RELAY_TOKEN : (s.relayToken == null ? "" : s.relayToken), v -> {
					if (!s.useDefaultRelay) {
						s.relayToken = v.trim();
						Settings.save();
					}
				});
		url.setEditable(!s.useDefaultRelay);
		token.setEditable(!s.useDefaultRelay);
		label("Статус: " + Relay.status(), left, y + 114, W);
		label("Порталы видны на серверах, но не в локальных мирах.", left, y + 138, W);
		label("Чтобы вписать свой адрес и токен — сними галочку.", left, y + 160, W);
	}
}
