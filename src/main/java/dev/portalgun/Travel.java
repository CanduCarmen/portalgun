package dev.portalgun;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Логика перемещений: телепорт по координатам, переход в другой локальный мир, заход на сервер, возврат.
 *
 * Правила по вещам:
 *  - в локальный мир вещи переносятся, если игрок был в локальном мире;
 *  - с сервера в локальный мир — только если включена галочка в настройках;
 *  - на сервер вещи НЕ переносятся и мод инвентарь на сервере не трогает.
 * (вещи копируются: в мире, откуда ушёл игрок, они остаются в его сейве)
 */
public final class Travel {
	public enum Kind {
		CURRENT, WORLD, SERVER
	}

	/** Где находится игрок / куда идёт. */
	public static final class Place {
		public final Kind kind;
		public final String value;
		public final String label;

		public Place(Kind kind, String value, String label) {
			this.kind = kind;
			this.value = value;
			this.label = label;
		}

		boolean same(Place o) {
			return o != null && kind == o.kind && value.equals(o.value);
		}
	}

	/** Куда возвращает обратный портал. */
	private static final class Back {
		final Place place;
		final double x;
		final double y;
		final double z;

		Back(Place place, double x, double y, double z) {
			this.place = place;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	/** Что сделать после того, как игрок зашёл в новый мир. */
	private static final class Pending {
		final double x;
		final double y;
		final double z;
		final boolean spawnReturnPortal;
		final boolean restoreItems;
		int wait = 0;

		Pending(double x, double y, double z, boolean spawnReturnPortal, boolean restoreItems) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.spawnReturnPortal = spawnReturnPortal;
			this.restoreItems = restoreItems;
		}
	}

	private static Back returnPoint;
	private static Pending pending;
	private static List<ItemStack> carried = new ArrayList<>();
	/** Откуда пришли при заходе на сервер: если войти не вышло, возвращаем сюда (а не в главное меню). */
	private static Back origin;
	/** Сколько тиков ещё ждём результат подключения (после успешного входа окно сокращается). */
	private static int originTicks = 0;
	private static boolean autoJoinHandled = false;
	private static int titleTicks = 0;

	private Travel() {
	}

	// ------------------------------------------------------------------ места

	public static Place here(Minecraft mc) {
		IntegratedServer srv = mc.getSingleplayerServer();
		if (srv != null) {
			String id = srv.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
			return new Place(Kind.WORLD, id, id);
		}
		ServerData sd = mc.getCurrentServer();
		if (sd != null) {
			return new Place(Kind.SERVER, sd.ip, sd.name);
		}
		return new Place(Kind.CURRENT, "", "");
	}

	/** Авто-цвет: синий — из локального мира не на сервер (мир → мир); зелёный — мир → сервер и всё, что начинается на сервере. */
	public static boolean autoBlue(Place from, Place to) {
		return from != null && from.kind == Kind.WORLD && (to == null || to.kind != Kind.SERVER);
	}

	public static Place destination() {
		Settings.Dest d = Settings.get().dest;
		Kind kind;
		try {
			kind = Kind.valueOf(d.kind);
		} catch (IllegalArgumentException e) {
			kind = Kind.CURRENT;
		}
		return new Place(kind, d.value == null ? "" : d.value, d.label == null ? "" : d.label);
	}

	private static void join(Place dest) {
		if (dest.kind == Kind.WORLD) {
			Compat.openWorld(dest.value);
		} else if (dest.kind == Kind.SERVER) {
			Compat.connect(dest.value, dest.label.isEmpty() ? dest.value : dest.label);
		}
	}

	// ------------------------------------------------------------------ вход в портал

	public static void enter(Portal portal) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer pl = mc.player;
		if (pl == null) {
			return;
		}
		PortalManager.remove(portal);
		Place here = here(mc);

		if (portal.role == Portal.Role.OUTGOING || portal.role == Portal.Role.REMOTE) {
			Place dest = portal.dest != null ? portal.dest : destination();
			CoordStore.Coord c = portal.destCoord != null ? portal.destCoord : CoordStore.selected();
			Back back = new Back(here, pl.getX(), pl.getY(), pl.getZ());
			if (dest.kind == Kind.CURRENT || dest.same(here)) {
				if (c == null) {
					Compat.msg("Точка координат не выбрана.");
					return;
				}
				returnPoint = back;
				Compat.teleport(c.x, c.y, c.z);
				PortalGunClient.schedule(15, Travel::spawnReturnPortalNearPlayer);
			} else {
				travel(dest, c, back, true);
			}
		} else {
			Back rp = returnPoint;
			if (rp == null) {
				return;
			}
			returnPoint = null;
			// запоминаем, откуда мы только что вернулись — чтобы на новом месте
			// тоже появился портал назад и можно было ходить туда-сюда сколько угодно раз
			Back backHere = new Back(here, pl.getX(), pl.getY(), pl.getZ());
			if (rp.place.kind == Kind.CURRENT || rp.place.same(here)) {
				returnPoint = backHere;
				Compat.teleport(rp.x, rp.y, rp.z);
				PortalGunClient.schedule(15, Travel::spawnReturnPortalNearPlayer);
			} else {
				travel(rp.place, null, backHere, true);
			}
		}
	}

	private static void travel(Place dest, CoordStore.Coord target, Back back, boolean spawnReturnPortal) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer pl = mc.player;
		boolean fromLocal = mc.getSingleplayerServer() != null;
		boolean toLocal = dest.kind == Kind.WORLD;

		carried = new ArrayList<>();
		if (toLocal && pl != null && (fromLocal || Settings.get().carryServerItems)) {
			carried = snapshot(pl);
		}
		if (back != null) {
			returnPoint = back;
			// если сервер не пустит, вернёмся обратно; для входа в свой же мир/сервер откат не нужен
			origin = dest.kind == Kind.SERVER && (back.place.kind == Kind.WORLD || back.place.kind == Kind.SERVER) ? back : null;
			originTicks = 20 * 60;
		}
		pending = new Pending(
				target == null ? Double.NaN : target.x,
				target == null ? Double.NaN : target.y,
				target == null ? Double.NaN : target.z,
				spawnReturnPortal, !carried.isEmpty());

		Compat.disconnect();
		// небольшая пауза, чтобы интегрированный сервер успел сохраниться и освободить session.lock
		PortalGunClient.schedule(10, () -> join(dest));
	}

	// ------------------------------------------------------------------ вещи

	private static List<ItemStack> snapshot(LocalPlayer pl) {
		Inventory inv = pl.getInventory();
		List<ItemStack> list = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			list.add(inv.getItem(i).copy());
		}
		return list;
	}

	/** Кладёт перенесённые вещи в инвентарь игрока в локальном мире (через интегрированный сервер). */
	private static void restoreItems() {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer srv = mc.getSingleplayerServer();
		if (srv == null || mc.player == null || carried.isEmpty()) {
			return;
		}
		final UUID id = mc.player.getUUID();
		final List<ItemStack> items = carried;
		carried = new ArrayList<>();
		srv.execute(() -> {
			ServerPlayer sp = srv.getPlayerList().getPlayer(id);
			if (sp == null) {
				return;
			}
			Inventory inv = sp.getInventory();
			int n = Math.min(items.size(), inv.getContainerSize());
			for (int i = 0; i < n; i++) {
				inv.setItem(i, items.get(i).copy());
			}
		});
	}

	// ------------------------------------------------------------------ события

	public static void onJoin() {
		if (pending != null) {
			pending.wait = 0;
		}
		if (origin != null) {
			originTicks = Math.min(originTicks, 200); // ещё 10 секунд ловим кик сразу после входа
		}
	}

	/** Не вышло зайти на сервер (отказ, кик, вайтлист, таймаут) — кидаем обратно в мир/сервер, откуда пришёл. */
	private static void tickFallback(Minecraft mc) {
		if (origin == null) {
			return;
		}
		if (--originTicks <= 0) {
			origin = null;
			return;
		}
		if (mc.level == null && Compat.currentScreen() instanceof DisconnectedScreen) {
			Back o = origin;
			origin = null;
			returnPoint = null;
			PortalGunClient.LOGGER.info("Could not join the server, going back to {}", o.place.label);
			pending = new Pending(o.x, o.y, o.z, false, false);
			PortalGunClient.schedule(5, () -> join(o.place));
		}
	}

	static void spawnReturnPortalNearPlayer() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			Portal rp = PortalManager.spawn(Portal.Role.RETURN, p);
			rp.blue = autoBlue(here(Minecraft.getInstance()), returnPoint == null ? null : returnPoint.place);
		}
	}

	public static void tick(Minecraft mc) {
		tickAutoJoin();
		tickFallback(mc);
		if (pending == null) {
			return;
		}
		if (mc.player == null || mc.level == null || Compat.currentScreen() != null) {
			pending.wait = 0;
			return;
		}
		if (++pending.wait < 30) { // даём миру прогрузиться
			return;
		}
		Pending p = pending;
		pending = null;
		if (p.restoreItems) {
			restoreItems();
		}
		if (!Double.isNaN(p.x)) {
			Compat.teleport(p.x, p.y, p.z);
		}
		if (p.spawnReturnPortal) {
			PortalGunClient.schedule(15, Travel::spawnReturnPortalNearPlayer);
		}
	}

	/** Режим «при запуске сразу в выбранный мир»: срабатывает один раз за запуск игры. */
	private static void tickAutoJoin() {
		if (autoJoinHandled) {
			return;
		}
		if (!(Compat.currentScreen() instanceof TitleScreen)) {
			return;
		}
		if (++titleTicks < 20) {
			return;
		}
		autoJoinHandled = true; // после этого выход в главное меню через паузу работает как обычно
		if (!Settings.get().autoJoinOnLaunch) {
			return;
		}
		Place dest = destination();
		if (dest.kind != Kind.CURRENT) {
			join(dest);
		}
	}
}
