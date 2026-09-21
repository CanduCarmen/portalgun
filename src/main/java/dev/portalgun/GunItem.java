package dev.portalgun;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Мод клиентский, поэтому «предмет» — это обычная палка с компонентом item_model = portalgun:portal_gun
 * (модель/текстура берутся из ресурсов мода). Выдаётся командой /give, поэтому на сервере нужны читы.
 * ПКМ — выстрел, Shift+ПКМ — меню.
 *
 * Режим без пушки: включается кнопкой в настройках меню или клавишей (по умолчанию G, меняется в Управлении).
 * Пока режим включён, ПКМ с любым предметом (или с пустой рукой) ведёт себя как с пушкой.
 * Повторное нажатие выключает режим — дальше играешь как обычно.
 */
public final class GunItem {
	public static final Identifier MODEL = Identifier.fromNamespaceAndPath(PortalGunClient.MOD_ID, "portal_gun");

	// глобальный счётчик клиента (PortalGunClient.ticks()), а не player.tickCount —
	// иначе после смены мира/сервера кулдаун "залипает" и пушка перестаёт стрелять
	private static long lastShotTick = Long.MIN_VALUE / 2;

	/** Режим без пушки (на время сессии, в файл не сохраняется — чтобы не застрять в нём после перезапуска). */
	private static boolean gunless = false;
	/** Тик, в котором действие уже выполнено (колбэки Fabric и опрос кнопки не должны сработать дважды). */
	private static long handledTick = Long.MIN_VALUE;
	private static boolean prevUse = false;
	private static KeyMapping toggleKey;

	private GunItem() {
	}

	// ------------------------------------------------------------------ клавиша и режим без пушки

	public static void registerKeys() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(PortalGunClient.MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.portalgun.toggle_gunless", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
	}

	public static boolean gunless() {
		return gunless;
	}

	public static void setGunless(boolean on) {
		gunless = on;
		prevUse = false;
		if (on) {
			Compat.msg("Режим без пушки ВКЛЮЧЁН: ПКМ — портал, Shift+ПКМ — выбрать место. Выключить: клавиша G или кнопка в меню.");
		} else {
			Compat.msg("Режим без пушки выключен — играй как обычно.");
		}
	}

	public static void toggleGunless() {
		setGunless(!gunless);
	}

	/** Вызывается в начале каждого клиентского тика (до увеличения счётчика тиков). */
	public static void tick(Minecraft mc) {
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) {
				if (mc.player != null) {
					toggleGunless();
				}
			}
		}
		boolean down = mc.options.keyUse.isDown();
		boolean pressed = down && !prevUse;
		prevUse = down;
		// пустая рука + воздух: ваниль вообще не вызывает useItem, поэтому нажатие ловим тут
		if (pressed && gunless && mc.player != null && Compat.currentScreen() == null) {
			handle(mc.player);
		}
	}

	// ------------------------------------------------------------------ обработка использования

	/** Пушка = палка с моделью пушки ИЛИ предмет, выбранный в настройках мода (как «инструмент» в Litematica). */
	public static boolean isGun(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (MODEL.equals(stack.get(DataComponents.ITEM_MODEL))) {
			return true;
		}
		String id = Settings.get().gunItem;
		return id != null && !id.isBlank() && id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	/**
	 * Вызывается из миксина ItemModelResolver при отрисовке любого предмета (инвентарь, рука, руки других игроков):
	 * если это предмет из настроек мода — рисуем его моделью портальной пушки.
	 */
	public static ItemStack withGunModel(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return stack;
		}
		String id = Settings.get().gunItem;
		if (id == null || id.isBlank() || MODEL.equals(stack.get(DataComponents.ITEM_MODEL))) {
			return stack;
		}
		if (!id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
			return stack;
		}
		ItemStack copy = stack.copy();
		copy.set(DataComponents.ITEM_MODEL, MODEL);
		return copy;
	}

	/** Приводит ввод к виду namespace:path и проверяет, что такой предмет существует. null — неверный ID. */
	public static String normalizeItemId(String raw) {
		Identifier id = Identifier.tryParse(raw.trim());
		if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
			return null;
		}
		return id.toString();
	}

	/** Fabric UseItemCallback. Срабатывает и на клиенте, и на сервере — работаем только на клиенте. */
	public static InteractionResult onUse(Player player, Level level, InteractionHand hand) {
		if (!level.isClientSide() || !(player instanceof LocalPlayer lp)) {
			return InteractionResult.PASS;
		}
		if (!isGun(player.getItemInHand(hand)) && !gunless) {
			return InteractionResult.PASS;
		}
		handle(lp);
		return InteractionResult.SUCCESS; // отменяет ванильное использование — на сервер ничего не уходит
	}

	/** С пушкой в руке (или в режиме без пушки) ПКМ по блоку не открывает сундуки и не ставит блоки. */
	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(gunless || isGun(player.getItemInHand(hand))) || !level.isClientSide() || !(player instanceof LocalPlayer lp)) {
			return InteractionResult.PASS;
		}
		handle(lp);
		return InteractionResult.SUCCESS;
	}

	/** То же для ПКМ по мобам/игрокам/жителям. */
	public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		if (!(gunless || isGun(player.getItemInHand(hand))) || !level.isClientSide() || !(player instanceof LocalPlayer lp)) {
			return InteractionResult.PASS;
		}
		handle(lp);
		return InteractionResult.SUCCESS;
	}

	private static void handle(LocalPlayer lp) {
		if (handledTick == PortalGunClient.ticks()) {
			return;
		}
		handledTick = PortalGunClient.ticks();
		if (lp.isShiftKeyDown()) {
			GunAnimations.settingsOpened(lp);
			Compat.setScreen(new PortalGunScreen());
		} else {
			shoot(lp, false);
		}
	}

	/** Открыть портал перед игроком. force = игнорировать кулдаун (кнопка «случайный сервер»). */
	public static void shoot(LocalPlayer p, boolean force) {
		if (!force && PortalGunClient.ticks() - lastShotTick < 10) {
			return;
		}
		Settings s = Settings.get();
		if ("CURRENT".equals(s.dest.kind) && CoordStore.selected() == null) {
			Compat.msg("Сначала выбери координаты: Shift+ПКМ (с пушкой или в режиме без пушки), или /portalgun");
			return;
		}
		lastShotTick = PortalGunClient.ticks();
		GunAnimations.shot(p);
		if (!s.unlimitedPortals) {
			PortalManager.trimOutgoing(Math.max(0, s.maxPortals - 1));
		}
		Portal portal = PortalManager.spawn(Portal.Role.OUTGOING, p);
		// снимок назначения: портал ведёт туда, куда целились в момент выстрела (и это же видят другие игроки)
		Travel.Place dest = Travel.destination();
		CoordStore.Coord c = CoordStore.selected();
		portal.dest = dest;
		portal.destCoord = c == null ? null : new CoordStore.Coord(c.name, c.x, c.y, c.z);
		portal.blue = Travel.autoBlue(Travel.here(Minecraft.getInstance()), dest);
	}

	/** Выдаёт пушку командой /give (нужны читы / права оператора). */
	public static void give() {
		Compat.command("give @s minecraft:stick[item_model=\"" + MODEL + "\",item_name=\"Portal Gun\",max_stack_size=1] 1");
	}
}
