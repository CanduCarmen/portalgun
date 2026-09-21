package dev.portalgun.mixin;

import dev.portalgun.GunItem;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Подмена модели: предмет из настроек мода рисуется моделью портальной пушки
 * (в инвентаре, в руке и в руках других игроков). require = 0 — если в какой-то версии игры
 * метод переименуют, мод просто не будет подменять модель, а не упадёт при запуске.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {
	@ModifyVariable(method = "appendItemLayers", at = @At("HEAD"), argsOnly = true, require = 0)
	private ItemStack portalgun$swapModel(ItemStack stack) {
		return GunItem.withGunModel(stack);
	}
}
