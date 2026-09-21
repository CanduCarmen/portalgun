package dev.portalgun;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Клиентская точка входа. Мод целиком клиентский: на сервер ничего не ставится. */
public class PortalGunClient implements ClientModInitializer {
	public static final String MOD_ID = "portalgun";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final class Task {
		int ticks;
		final Runnable action;

		Task(int ticks, Runnable action) {
			this.ticks = ticks;
			this.action = action;
		}
	}

	private static final List<Task> TASKS = new ArrayList<>();

	/**
	 * Глобальный счётчик тиков клиента — растёт всегда, пока запущена игра, и НЕ зависит от
	 * player.tickCount (который у каждого мира свой и может быть меньше, чем в предыдущем мире).
	 * Используется вместо player.tickCount там, где нужен кулдаун, переживающий смену мира/сервера.
	 */
	private static long clientTicks = 0;

	public static long ticks() {
		return clientTicks;
	}

	/** Выполнить действие через N клиентских тиков. */
	public static void schedule(int ticks, Runnable action) {
		TASKS.add(new Task(Math.max(1, ticks), action));
	}

	@Override
	public void onInitializeClient() {
		Settings.load();
		CoordStore.load();
		PortalModels.load();
		PortalRenderer.register();

		GunItem.registerKeys();
		UseItemCallback.EVENT.register(GunItem::onUse);
		UseBlockCallback.EVENT.register(GunItem::onUseBlock);
		UseEntityCallback.EVENT.register(GunItem::onUseEntity);
		ClientTickEvents.END_CLIENT_TICK.register(PortalGunClient::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Travel.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PortalManager.clear());
		// /portalgun, /portalgun give — перехватываются на клиенте и на сервер не уходят
		ClientSendMessageEvents.ALLOW_COMMAND.register(PortalGunClient::onCommand);
		// «Teleported ...» / ошибки от нашего /tp в чат не попадают
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> Compat.allowGameMessage(message));

		LOGGER.info("Portal Gun (client) loaded");
	}

	private static void tick(Minecraft mc) {
		GunItem.tick(mc); // до clientTicks++, чтобы дедупликация внутри одного тика работала
		clientTicks++;
		if (!TASKS.isEmpty()) {
			List<Task> due = new ArrayList<>();
			for (Task t : TASKS) {
				if (--t.ticks <= 0) {
					due.add(t);
				}
			}
			TASKS.removeAll(due);
			for (Task t : due) {
				t.action.run();
			}
		}
		Relay.tick(mc);
		Travel.tick(mc);
		PortalManager.tick(mc);
	}

	private static boolean onCommand(String command) {
		String c = command.trim();
		if (!c.equals("portalgun") && !c.startsWith("portalgun ")) {
			return true;
		}
		String arg = c.length() > "portalgun".length() ? c.substring("portalgun".length()).trim() : "";
		if (arg.equals("give")) {
			GunItem.give();
		} else if (arg.equals("reload")) {
			// перечитать config/portalgun/portal.bbmodel (или встроенную модель) без перезапуска игры
			PortalModels.load();
			Compat.msg(PortalModels.portal() == null ? "Модель портала не загрузилась, смотри лог." : "Модель портала перезагружена.");
		} else {
			// откладываем на тик: чат сам закрывает экран после отправки команды
			schedule(1, () -> Compat.setScreen(new PortalGunScreen()));
		}
		return false;
	}
}
