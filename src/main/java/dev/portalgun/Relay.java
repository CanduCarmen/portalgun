package dev.portalgun;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Обмен порталами между игроками через маленький внешний «релей» (relay/portalgun_relay.py).
 * Мод клиентский, а ванильный сервер чужие пакеты не пересылает, поэтому без общего релея игроки друг друга не увидят.
 *
 * Раз в секунду клиент отправляет свои порталы (только на многопользовательском сервере и только если включено в
 * настройках) и получает порталы других игроков с ТОГО ЖЕ сервера и из ТОГО ЖЕ измерения.
 */
public final class Relay {
	/** Портал в формате обмена. */
	public static final class WirePortal {
		public String pid = "";
		public double x;
		public double y;
		public double z;
		public double yaw;
		public int age;
		public String kind = "";
		public String value = "";
		public String label = "";
		public boolean hasCoord;
		public double cx;
		public double cy;
		public double cz;
		public String cname = "";
		// заполняет релей
		public String owner = "";
		public String ownerName = "";
	}

	private static final class Out {
		String id;
		String name;
		String place;
		String dim;
		List<WirePortal> portals = new ArrayList<>();
	}

	private static final class In {
		List<WirePortal> portals = new ArrayList<>();
		int players;
	}

	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private static final AtomicReference<List<WirePortal>> INCOMING = new AtomicReference<>();

	private static volatile String status = "Выключено";
	private static volatile boolean inFlight = false;
	private static boolean wasActive = false;
	private static int timer = 0;

	private Relay() {
	}

	public static String status() {
		return status;
	}

	/** Адрес сервера в едином виде (нижний регистр, без порта по умолчанию). */
	static String normalizePlace(String ip) {
		String s = ip == null ? "" : ip.trim().toLowerCase(java.util.Locale.ROOT);
		return s.endsWith(":25565") ? s.substring(0, s.length() - 6) : s;
	}

	public static void tick(Minecraft mc) {
		Settings s = Settings.get();
		boolean online = mc.player != null && mc.level != null && mc.getSingleplayerServer() == null && mc.getCurrentServer() != null;
		boolean configured = !s.effectiveRelayUrl().isBlank();
		if (!s.shareEnabled || !configured || !online) {
			if (wasActive) {
				PortalManager.clearRemote();
				wasActive = false;
			}
			status = !s.shareEnabled ? "Выключено"
					: !configured ? "Не указан адрес релея"
					: "Ждёт подключения к серверу";
			return;
		}
		wasActive = true;

		List<WirePortal> in = INCOMING.getAndSet(null);
		if (in != null) {
			PortalManager.applyRemote(in);
		}
		if (++timer < 20 || inFlight) {
			return;
		}
		timer = 0;
		send(mc, s);
	}

	private static void send(Minecraft mc, Settings s) {
		Out out = new Out();
		out.id = mc.player.getUUID().toString();
		out.name = mc.player.getName().getString();
		out.place = normalizePlace(mc.getCurrentServer().ip);
		out.dim = String.valueOf(mc.level.dimension());
		for (Portal p : PortalManager.snapshot()) {
			if (p.role != Portal.Role.OUTGOING || p.dest == null || p.dest.kind == Travel.Kind.WORLD || p.expired()) {
				continue;
			}
			WirePortal w = new WirePortal();
			w.pid = p.pid;
			w.x = p.x;
			w.y = p.y;
			w.z = p.z;
			w.yaw = p.yawRad;
			w.age = p.age;
			w.kind = p.dest.kind.name();
			w.value = p.dest.value;
			w.label = p.dest.label;
			if (p.destCoord != null) {
				w.hasCoord = true;
				w.cx = p.destCoord.x;
				w.cy = p.destCoord.y;
				w.cz = p.destCoord.z;
				w.cname = p.destCoord.name;
			}
			out.portals.add(w);
		}
		// релей принимает только первые 4 портала — отправляем самые новые
		if (out.portals.size() > 4) {
			out.portals = new ArrayList<>(out.portals.subList(out.portals.size() - 4, out.portals.size()));
		}

		HttpRequest req;
		try {
			String base = s.effectiveRelayUrl();
			if (!base.startsWith("http://") && !base.startsWith("https://")) {
				base = "http://" + base;
			}
			while (base.endsWith("/")) {
				base = base.substring(0, base.length() - 1);
			}
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/sync"))
					.timeout(Duration.ofSeconds(4))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(out)));
			if (!s.effectiveRelayToken().isBlank()) {
				b.header("X-Token", s.effectiveRelayToken());
			}
			req = b.build();
		} catch (IllegalArgumentException e) {
			status = "Неверный адрес релея";
			return;
		}

		inFlight = true;
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			inFlight = false;
			if (err != null) {
				Throwable c = err.getCause() != null ? err.getCause() : err;
				status = "Ошибка: " + c.getClass().getSimpleName();
				return;
			}
			if (resp.statusCode() != 200) {
				status = "Ошибка релея: HTTP " + resp.statusCode();
				return;
			}
			try {
				In parsed = GSON.fromJson(resp.body(), In.class);
				if (parsed != null && parsed.portals != null) {
					INCOMING.set(parsed.portals);
					status = "Подключено, игроков рядом: " + parsed.players;
				}
			} catch (RuntimeException e) {
				status = "Ошибка: неверный ответ релея";
			}
		});
	}
}
