package dev.portalgun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Рисует порталы моделью из portal.bbmodel.
 *
 * Никаких процедурных заглушек: ни вращения UV, ни масштаба из центра,
 * ни частиц. Всё движение — только из анимаций "open" / "close" / "whirlpool"
 * в .bbmodel. Если у анимации нет ключей — соответствующая фаза просто
 * не двигается (OPENING вообще не рисуется, CLOSING рисуется статично).
 */
public final class PortalRenderer {
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(PortalGunClient.MOD_ID, "textures/model/portal.png");

	/** Голубой вариант (оттенок сдвинут; генерится tools/make_blue_portal.py из portal.png). */
	public static final Identifier TEXTURE_BLUE = Identifier.fromNamespaceAndPath(PortalGunClient.MOD_ID, "textures/model/portal_blue.png");

	/** Серая заготовка (tools/make_gray_portal.py): окрашивается своим цветом из меню. */
	public static final Identifier TEXTURE_GRAY = Identifier.fromNamespaceAndPath(PortalGunClient.MOD_ID, "textures/model/portal_gray.png");

	private static final int FULL_BRIGHT = 0xF000F0;
	private static final int NO_OVERLAY = 10 << 16;

	private PortalRenderer() {
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(context ->
				collect(context.poseStack(), context.submitNodeCollector(), context.levelState().cameraRenderState.pos));
	}

	private static void collect(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cam) {
		BbModel model = PortalModels.portal();
		if (model == null) {
			return;
		}
		List<Portal> portals = PortalManager.snapshot();
		if (portals.isEmpty()) {
			return;
		}
		RenderType typeGreen = RenderTypes.entityTranslucent(TEXTURE);
		RenderType typeBlue = RenderTypes.entityTranslucent(TEXTURE_BLUE);
		RenderType typeGray = RenderTypes.entityTranslucent(TEXTURE_GRAY);
		Settings st = Settings.get();
		final int[] tint = hsv(st.portalHue);

		for (Portal p : portals) {
			Portal.Phase phase = p.phase();
			double seconds = p.seconds();

			List<BbModel.Layer> layers = new ArrayList<>(3);

			if (model.hasKeyframes("whirlpool")) {
				layers.add(new BbModel.Layer("whirlpool", seconds));
			}
			if (phase == Portal.Phase.OPENING) {
				if (model.hasKeyframes("open")) {
					layers.add(new BbModel.Layer("open", p.phaseSeconds()));
				} else {
					continue;
				}
			} else if (phase == Portal.Phase.CLOSING) {
				if (model.hasKeyframes("close")) {
					layers.add(new BbModel.Layer("close", p.phaseSeconds()));
				}
			}

			final RenderType type = st.autoColor ? (p.blue ? typeBlue : typeGreen) : typeGray;
			final int[] rgb = st.autoColor ? new int[] {255, 255, 255} : tint;
			final double scale = 1.0;
			final double uvSpin = 0.0;
			final int alpha = 255;

			poseStack.pushPose();
			poseStack.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
			collector.submitCustomGeometry(poseStack, type, (pose, buffer) ->
					model.emit(p.yawRad, scale, uvSpin, layers,
							(pos, uv) -> quad(pose, buffer, pos, uv, rgb, alpha)));
			poseStack.popPose();
		}
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, double[] pos, float[] uv, int[] rgb, int alpha) {
		for (int i = 0; i < 4; i++) {
			buffer.addVertex(pose.pose(), (float) pos[i * 3], (float) pos[i * 3 + 1], (float) pos[i * 3 + 2])
					.setColor(rgb[0], rgb[1], rgb[2], alpha)
					.setUv(uv[i * 2], uv[i * 2 + 1])
					.setOverlay(NO_OVERLAY)
					.setLight(FULL_BRIGHT)
					.setNormal(pose, 0.0f, 1.0f, 0.0f);
		}
	}

	/** Оттенок 0..1 (насыщенность и яркость максимальные) -> {r, g, b} 0..255. */
	public static int[] hsv(float hue) {
		float h = (hue - (float) Math.floor(hue)) * 6.0f;
		int i = (int) h;
		float f = h - i;
		float q = 1.0f - f;
		float r;
		float g;
		float b;
		switch (i % 6) {
			case 0 -> { r = 1; g = f; b = 0; }
			case 1 -> { r = q; g = 1; b = 0; }
			case 2 -> { r = 0; g = 1; b = f; }
			case 3 -> { r = 0; g = q; b = 1; }
			case 4 -> { r = f; g = 0; b = 1; }
			default -> { r = 1; g = 0; b = q; }
		}
		return new int[] {Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)};
	}
}
