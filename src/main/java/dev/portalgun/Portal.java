package dev.portalgun;

/**
 * Портал. Внешний вид — модель Blockbench (portal.bbmodel).
 * Анимации из модели: "open", "close", "whirlpool". Никаких процедурных
 * заглушек (scale, particles) — если ключей у анимации нет, фаза просто
 * визуально ничего не делает.
 */
public final class Portal {
	public enum Role {
		OUTGOING,
		RETURN,
		/** Портал другого игрока (пришёл через релей). */
		REMOTE
	}

	public enum Phase {
		OPENING, IDLE, CLOSING
	}

	private static final int DEFAULT_OPEN_TICKS = 20;
	private static final int DEFAULT_CLOSE_TICKS = 20;

	public final Role role;
	public final double x;
	public final double y;
	public final double z;
	public final double yawRad;
	/** Куда ведёт портал (снимок на момент выстрела). null — брать из текущих настроек при входе. */
	public Travel.Place dest;
	public CoordStore.Coord destCoord;
	/** Синий вариант текстуры (авто-цвет): портал между локальными мирами. Иначе зелёный: мир → сервер и всё остальное. */
	public boolean blue;
	/** Для чужих порталов: владелец и ключ owner:pid. Для своих pid — случайный id, который уходит на релей. */
	public String ownerName;
	public String key;
	public final String pid = java.util.UUID.randomUUID().toString();
	public int age = 0;
	/** Сколько тиков живёт этот портал (берётся из настроек в момент создания). */
	public final int lifetime = lifetimeTicks();
	public int grace;
	private long tickNanos = System.nanoTime();

	public Portal(Role role, double x, double y, double z, double yawRad, int grace) {
		this.role = role;
		this.x = x;
		this.y = y;
		this.z = z;
		this.yawRad = yawRad;
		this.grace = grace;
	}

	/** Время жизни нового портала в тиках: из настроек, но не меньше, чем нужно на открытие и закрытие. */
	public static int lifetimeTicks() {
		Settings s = Settings.get();
		if (s.unlimitedTime) {
			return Integer.MAX_VALUE;
		}
		long t = Math.max((long) s.portalSeconds * 20L, (long) openTicks() + closeTicks() + 20L);
		return (int) Math.min(Integer.MAX_VALUE, t);
	}

	public static int openTicks() {
		return PortalModels.animTicks("open", DEFAULT_OPEN_TICKS);
	}

	public static int closeTicks() {
		return PortalModels.animTicks("close", DEFAULT_CLOSE_TICKS);
	}

	public static double halfWidth() {
		BbModel m = PortalModels.portal();
		return m == null ? 2.0 : Math.max(0.25, m.halfWidth());
	}

	public static double minY() {
		BbModel m = PortalModels.portal();
		return m == null ? 0.0 : m.minY();
	}

	public static double maxY() {
		BbModel m = PortalModels.portal();
		return m == null ? 4.0 : Math.max(0.5, m.maxY());
	}

	public void tick() {
		age++;
		tickNanos = System.nanoTime();
		if (grace > 0) {
			grace--;
		}
	}

	public double partial() {
		return Math.max(0.0, Math.min(1.0, (System.nanoTime() - tickNanos) / 50_000_000.0));
	}

	public Phase phase() {
		if (age < openTicks()) {
			return Phase.OPENING;
		}
		if (age >= lifetime - closeTicks()) {
			return Phase.CLOSING;
		}
		return Phase.IDLE;
	}

	public double seconds() {
		return (age + partial()) / 20.0;
	}

	public double phaseSeconds() {
		return switch (phase()) {
			case OPENING -> seconds();
			case CLOSING -> (age + partial() - (lifetime - closeTicks())) / 20.0;
			case IDLE -> 0.0;
		};
	}

	public boolean expired() {
		return age >= lifetime;
	}

	public boolean enterable() {
		return grace <= 0 && phase() == Phase.IDLE;
	}

	private double fx() {
		return -Math.sin(yawRad);
	}

	private double fz() {
		return Math.cos(yawRad);
	}

	private double lx() {
		return Math.cos(yawRad);
	}

	private double lz() {
		return Math.sin(yawRad);
	}

	public boolean contains(double px, double py, double pz) {
		double dx = px - x;
		double dz = pz - z;
		double dy = py - y;
		double forward = dx * fx() + dz * fz();
		double lateral = dx * lx() + dz * lz();
		return Math.abs(forward) < 0.6 && Math.abs(lateral) < halfWidth() && dy > minY() && dy < maxY();
	}
}