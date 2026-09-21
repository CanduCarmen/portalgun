package dev.portalgun;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;

/** Все активные порталы (только клиент). Очищается при выходе из мира. */
public final class PortalManager {
	private static final List<Portal> PORTALS = new ArrayList<>();
	private static final double DISTANCE = 3.0;

	private PortalManager() {
	}

	public static void clear() {
		PORTALS.clear();
	}

	public static List<Portal> snapshot() {
		return new ArrayList<>(PORTALS);
	}

	public static void remove(Portal p) {
		PORTALS.remove(p);
	}

	public static void removeRole(Portal.Role role) {
		PORTALS.removeIf(p -> p.role == role);
	}

	/** Оставляет не больше keep своих порталов (OUTGOING), убирая самые старые. */
	public static void trimOutgoing(int keep) {
		int count = 0;
		for (Portal p : PORTALS) {
			if (p.role == Portal.Role.OUTGOING) {
				count++;
			}
		}
		java.util.Iterator<Portal> it = PORTALS.iterator();
		while (it.hasNext() && count > keep) {
			if (it.next().role == Portal.Role.OUTGOING) {
				it.remove();
				count--;
			}
		}
	}

	public static Portal spawn(Portal.Role role, LocalPlayer player) {
		double yaw = Math.toRadians(player.getYRot());
		double x = player.getX() + (-Math.sin(yaw)) * DISTANCE;
		double z = player.getZ() + Math.cos(yaw) * DISTANCE;
		int grace = role == Portal.Role.RETURN ? 20 : 0;
		Portal p = new Portal(role, x, player.getY(), z, yaw, grace);
		PORTALS.add(p);
		return p;
	}

	/** Обновить чужие порталы по ответу релея (вызывается из основного потока). */
	public static void applyRemote(java.util.List<Relay.WirePortal> list) {
		java.util.Set<String> keep = new java.util.HashSet<>();
		int n = 0;
		for (Relay.WirePortal w : list) {
			if (w == null || w.pid == null || w.owner == null || n++ >= 64) {
				continue;
			}
			if (!Double.isFinite(w.x) || !Double.isFinite(w.y) || !Double.isFinite(w.z) || !Double.isFinite(w.yaw)) {
				continue;
			}
			Travel.Kind kind;
			try {
				kind = Travel.Kind.valueOf(w.kind);
			} catch (IllegalArgumentException | NullPointerException e) {
				continue;
			}
			// чужие локальные миры у нас не открыть; телепорт по координатам нужен с координатами
			if (kind == Travel.Kind.WORLD || (kind == Travel.Kind.CURRENT && !w.hasCoord)
					|| (kind == Travel.Kind.SERVER && (w.value == null || w.value.isBlank()))) {
				continue;
			}
			String key = w.owner + ":" + w.pid;
			keep.add(key);
			boolean exists = false;
			for (Portal p : PORTALS) {
				if (p.role == Portal.Role.REMOTE && key.equals(p.key)) {
					exists = true;
					break;
				}
			}
			if (exists) {
				continue;
			}
			Portal p = new Portal(Portal.Role.REMOTE, w.x, w.y, w.z, w.yaw, 0);
			p.key = key;
			p.ownerName = w.ownerName;
			p.age = Math.max(0, Math.min(w.age, p.lifetime - 1));
			String label = w.label == null || w.label.isBlank() ? w.value : w.label;
			p.dest = new Travel.Place(kind, w.value == null ? "" : w.value, label == null ? "" : label);
			if (w.hasCoord) {
				p.destCoord = new CoordStore.Coord(w.cname == null ? "" : w.cname, w.cx, w.cy, w.cz);
			}
			PORTALS.add(p);
		}
		PORTALS.removeIf(p -> p.role == Portal.Role.REMOTE && !keep.contains(p.key));
	}

	public static void clearRemote() {
		PORTALS.removeIf(p -> p.role == Portal.Role.REMOTE);
	}

	public static void tick(Minecraft mc) {
		if (PORTALS.isEmpty() || mc.player == null || mc.level == null) {
			return;
		}
		LocalPlayer player = mc.player;
		Portal entered = null;
		for (Portal p : new ArrayList<>(PORTALS)) {
			p.tick();
			if (p.expired()) {
				PORTALS.remove(p);
				continue;
			}
			if (entered == null && p.enterable() && p.contains(player.getX(), player.getY() + 0.9, player.getZ())) {
				entered = p;
			}
		}
		if (entered != null) {
			Travel.enter(entered);
		}
	}
}