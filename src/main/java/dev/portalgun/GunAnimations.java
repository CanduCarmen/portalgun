package dev.portalgun;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

/**
 * Анимации пушки — ЗАГЛУШКИ.
 *  - idle: анимированная текстура (portal_gun.png.mcmeta, 2 кадра);
 *  - settings: кольцо частиц вокруг игрока при открытии меню;
 *  - shot: взмах руки + луч из частиц.
 * Сюда потом можно повесить настоящие анимации и звуки.
 */
public final class GunAnimations {
	private GunAnimations() {
	}

	public static void shot(LocalPlayer p) {
		ClientLevel level = Minecraft.getInstance().level;
		p.swing(InteractionHand.MAIN_HAND);
		if (level == null) {
			return;
		}
		double yaw = Math.toRadians(p.getYRot());
		double pitch = Math.toRadians(p.getXRot());
		double lx = -Math.sin(yaw) * Math.cos(pitch);
		double ly = -Math.sin(pitch);
		double lz = Math.cos(yaw) * Math.cos(pitch);
		for (double t = 0.6; t <= 3.0; t += 0.2) {
			Compat.dust(level, p.getX() + lx * t, p.getEyeY() + ly * t - 0.2, p.getZ() + lz * t);
		}
	}

	public static void settingsOpened(LocalPlayer p) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		for (int i = 0; i < 16; i++) {
			double a = i * Math.PI / 8.0;
			Compat.dust(level, p.getX() + Math.cos(a) * 0.7, p.getY() + 1.0, p.getZ() + Math.sin(a) * 0.7);
		}
	}
}
