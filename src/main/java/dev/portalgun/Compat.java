package dev.portalgun;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Locale;

/**
 * ВСЕ вызовы Minecraft API, которые чаще всего ломаются между версиями, собраны здесь.
 * Если после обновления игры мод не компилируется — 90% правок будет только в этом файле.
 */
public final class Compat {
	private static final DustParticleOptions DUST = new DustParticleOptions(0x39FF14, 0.9f);

	private Compat() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------------------------------------------------------------- GUI

	/** В 26.2 экраны переехали из Minecraft в Gui (Minecraft.getInstance().gui). */
	public static void setScreen(Screen screen) {
		mc().gui.setScreen(screen);
	}

	public static Screen currentScreen() {
		return mc().gui.screen();
	}

	/** Сообщение игроку в чат (только на его клиенте, никуда не отправляется). */
	public static void msg(String text) {
		LocalPlayer p = mc().player;
		if (p != null) {
			p.sendSystemMessage(Component.literal("[Портальная пушка] " + text));
		}
	}

	// ---------------------------------------------------------------- команды / телепорт

	/** Отправляет команду от лица игрока (без слэша). Нужны права: читы/оп. */
	public static void command(String cmd) {
		LocalPlayer p = mc().player;
		if (p != null) {
			p.connection.sendCommand(cmd);
		}
	}

	/** До какого тика клиента глушим ответ сервера на наш /tp (успех и ошибки). */
	private static long tpFeedbackUntil = Long.MIN_VALUE;

	/** Fabric ALLOW_GAME: false — сообщение не показывать. Глушит только ответы на /tp, отправленный модом. */
	public static boolean allowGameMessage(Component message) {
		if (PortalGunClient.ticks() > tpFeedbackUntil) {
			return true;
		}
		boolean hide = false;
		if (message.getContents() instanceof TranslatableContents tc) {
			String k = tc.getKey();
			hide = k.startsWith("commands.teleport.") || k.startsWith("command.");
		}
		if (!hide) {
			// без прав (не оп) сервер отвечает «Unknown or incomplete command» + вторая строка с текстом команды и <--[HERE]
			String t = message.getString().toLowerCase(Locale.ROOT);
			hide = t.contains("teleport") || t.contains("телепорт") || t.contains("[here]")
					|| t.contains("unknown or incomplete") || t.contains("tp @s") || t.contains("неизвестн") && t.contains("команд");
		}
		if (hide) {
			PortalGunClient.LOGGER.info("Hidden teleport feedback: {}", message.getString());
		}
		return !hide;
	}

	public static void teleport(double x, double y, double z) {
		tpFeedbackUntil = PortalGunClient.ticks() + 200; // 10 секунд
		command(String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f", x, y, z));
	}

	// ---------------------------------------------------------------- частицы (заглушка порталов/анимаций)

	public static void dust(ClientLevel level, double x, double y, double z) {
		level.addParticle(DUST, x, y, z, 0.0, 0.0, 0.0);
	}

	// ---------------------------------------------------------------- вход/выход из миров

	/** Выйти из текущего мира/сервера так же, как кнопка "Save and Quit" в меню паузы. */
	public static void disconnect() {
		Minecraft mc = mc();
		boolean local = mc.isLocalServer();
		if (mc.level != null) {
			mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
		}
		if (local) {
			mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
		} else {
			mc.disconnect(new TitleScreen(), false);
		}
	}

	/** Открыть локальный мир по имени папки в saves/. */
	public static void openWorld(String levelId) {
		mc().createWorldOpenFlows().openWorld(levelId, () -> setScreen(new TitleScreen()));
	}

	/** Подключиться к серверу по IP, как из меню "Сетевая игра". */
	public static void connect(String ip, String name) {
		ServerAddress address = ServerAddress.parseString(ip);
		ServerData data = new ServerData(name, ip, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(new TitleScreen(), mc(), address, data, false, null);
	}
}
