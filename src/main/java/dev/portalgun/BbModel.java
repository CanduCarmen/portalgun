package dev.portalgun;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Мини-рантайм для моделей Blockbench (.bbmodel, формат "Generic Model").
 *
 * НЕ зависит от классов Minecraft: на вход — JSON, на выход — набор квадов (позиции в блоках + UV) через
 * {@link QuadSink}. Отрисовку квадов делает {@link PortalRenderer}. Поэтому файл переживает смену версий игры.
 *
 * Что поддерживается:
 *  - кубы (elements type=cube): from/to/origin/rotation/inflate, UV и поворот UV у каждой грани;
 *  - группы (outliner) любой вложенности: origin/rotation;
 *  - анимации: каналы position / rotation / scale у групп и кубов, интерполяция linear / step / catmullrom
 *    (bezier считается как linear), режимы loop = once / hold / loop, несколько слоёв одновременно.
 *
 * Поворот (rotation) интерполируется по кратчайшей дуге: 0° → 360° идёт вперёд,
 * а не «докручивается до половины и обратно».
 */
public final class BbModel {
	@FunctionalInterface
	public interface QuadSink {
		void quad(double[] pos, float[] uv);
	}

	@FunctionalInterface
	public interface TexturedQuadSink {
		void quad(double[] pos, float[] uv, int textureIndex);
	}

	public record Layer(String animation, double time) {
	}

	private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

	private static final int[][] FACE_CORNERS = {
			{1, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0},
			{1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1},
			{0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1},
			{0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0},
			{0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1},
			{0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0},
	};

	private static final class Face {
		boolean visible = true;
		double u1;
		double v1;
		double u2;
		double v2;
		int rotation;
		int textureIndex = -1;
	}

	private static final class Cube {
		final double[] from = new double[3];
		final double[] to = new double[3];
		double inflate;
		final Face[] faces = new Face[6];
	}

	private static final class Node {
		String uuid = "";
		final double[] origin = new double[3];
		final double[] rotation = new double[3];
		Cube cube;
		final List<Node> children = new ArrayList<>();
	}

	private static final class Key {
		double time;
		double[][] pts = {{0, 0, 0}};
		String interp = "linear";
		/** Для rotation: true, если канал крутит вокруг оси — тогда интерполируем по кратчайшей дуге. */
		boolean isRotation;

		double[] pre() {
			return pts[0];
		}

		double[] post() {
			return pts[pts.length - 1];
		}
	}

	private static final class Animator {
		final List<Key> pos = new ArrayList<>();
		final List<Key> rot = new ArrayList<>();
		final List<Key> scl = new ArrayList<>();
	}

	private static final class Anim {
		String loop = "once";
		double length;
		boolean hasKeys;
		final Map<String, Animator> animators = new HashMap<>();

		double localTime(double t) {
			if (length <= 0.0) {
				return 0.0;
			}
			if ("loop".equals(loop)) {
				double m = t % length;
				return m < 0 ? m + length : m;
			}
			return Math.max(0.0, Math.min(t, length));
		}
	}

	private static final class TextureInfo {
		final String name;
		final int width;
		final int height;

		TextureInfo(String name, int width, int height) {
			this.name = name;
			this.width = width;
			this.height = height;
		}
	}

	private final double resW;
	private final double resH;
	private final List<TextureInfo> textures;
	private final Map<String, JsonObject> cubeMeta = new HashMap<>();
	private final List<Node> roots = new ArrayList<>();
	private final Map<String, Anim> anims = new HashMap<>();
	private final double[] bounds = {0, 0, 0, 0, 0, 0};

	// ------------------------------------------------------------------ публичное API

	public boolean has(String animation) {
		return anims.containsKey(animation);
	}

	public boolean hasKeyframes(String animation) {
		Anim a = anims.get(animation);
		return a != null && a.hasKeys;
	}

	public double length(String animation) {
		Anim a = anims.get(animation);
		return a == null ? 0.0 : a.length;
	}

	public double halfWidth() {
		return Math.max(Math.abs(bounds[0]), Math.abs(bounds[3]));
	}

	public double minY() {
		return bounds[1];
	}

	public double maxY() {
		return bounds[4];
	}

	private double[] centerPx() {
		return new double[]{(bounds[0] + bounds[3]) * 16.0, (bounds[1] + bounds[4]) * 16.0, (bounds[2] + bounds[5]) * 16.0};
	}

	public void emit(double yaw, double scale, double uvSpin, List<Layer> layers, QuadSink sink) {
		emit(yaw, scale, uvSpin, layers, (pos, uv, textureIndex) -> sink.quad(pos, uv));
	}

	public void emit(double yaw, double scale, double uvSpin, List<Layer> layers, TexturedQuadSink sink) {
		double[] c = centerPx();
		M root = M.rotY(-yaw)
				.mul(M.translate(c[0], c[1], c[2]))
				.mul(M.scale(scale, scale, scale))
				.mul(M.translate(-c[0], -c[1], -c[2]));
		for (Node n : roots) {
			walk(n, root, layers, uvSpin, sink);
		}
	}

	public int textureCount() {
		return textures.size();
	}

	public String textureName(int index) {
		return index >= 0 && index < textures.size() ? textures.get(index).name : null;
	}

	public int textureWidth(int index) {
		return index >= 0 && index < textures.size() ? textures.get(index).width : (int) resW;
	}

	public int textureHeight(int index) {
		return index >= 0 && index < textures.size() ? textures.get(index).height : (int) resH;
	}

	// ------------------------------------------------------------------ обход и геометрия

	private void walk(Node n, M parent, List<Layer> layers, double uvSpin, TexturedQuadSink sink) {
		M world = parent.mul(local(n, layers));
		if (n.cube != null) {
			emitCube(n.cube, world, uvSpin, sink);
		}
		for (Node ch : n.children) {
			walk(ch, world, layers, uvSpin, sink);
		}
	}

	private M local(Node n, List<Layer> layers) {
		double px = 0;
		double py = 0;
		double pz = 0;
		double rx = Math.toRadians(n.rotation[0]);
		double ry = Math.toRadians(n.rotation[1]);
		double rz = Math.toRadians(n.rotation[2]);
		double sx = 1;
		double sy = 1;
		double sz = 1;
		if (layers != null) {
			for (Layer l : layers) {
				Anim a = anims.get(l.animation());
				if (a == null) {
					continue;
				}
				Animator an = a.animators.get(n.uuid);
				if (an == null) {
					continue;
				}
				double t = a.localTime(l.time());
				if (!an.pos.isEmpty()) {
					double[] v = eval(an.pos, t);
					px += -v[0];
					py += v[1];
					pz += v[2];
				}
				if (!an.rot.isEmpty()) {
					double[] v = eval(an.rot, t);
					rx += Math.toRadians(-v[0]);
					ry += Math.toRadians(-v[1]);
					rz += Math.toRadians(v[2]);
				}
				if (!an.scl.isEmpty()) {
					double[] v = eval(an.scl, t);
					sx *= v[0];
					sy *= v[1];
					sz *= v[2];
				}
			}
		}
		double[] o = n.origin;
		return M.translate(o[0] + px, o[1] + py, o[2] + pz)
				.mul(M.rotEuler(rx, ry, rz))
				.mul(M.scale(sx, sy, sz))
				.mul(M.translate(-o[0], -o[1], -o[2]));
	}

	private void emitCube(Cube cube, M world, double uvSpin, TexturedQuadSink sink) {
		double[][] ft = {
				{cube.from[0] - cube.inflate, cube.from[1] - cube.inflate, cube.from[2] - cube.inflate},
				{cube.to[0] + cube.inflate, cube.to[1] + cube.inflate, cube.to[2] + cube.inflate}
		};
		for (int f = 0; f < 6; f++) {
			Face face = cube.faces[f];
			if (face == null || !face.visible) {
				continue;
			}
			double[] pos = new double[12];
			double[] tmp = new double[3];
			for (int i = 0; i < 4; i++) {
				int[] sel = FACE_CORNERS[f];
				double x = ft[sel[i * 3]][0];
				double y = ft[sel[i * 3 + 1]][1];
				double z = ft[sel[i * 3 + 2]][2];
				world.apply(x, y, z, tmp);
				pos[i * 3] = tmp[0] / 16.0;
				pos[i * 3 + 1] = tmp[1] / 16.0;
				pos[i * 3 + 2] = tmp[2] / 16.0;
			}
			int tex = face.textureIndex;
			double texW = textureWidth(tex);
			double texH = textureHeight(tex);
			double u1 = face.u1 / texW;
			double v1 = face.v1 / texH;
			double u2 = face.u2 / texW;
			double v2 = face.v2 / texH;
			double[][] base = {{u1, v1}, {u2, v1}, {u2, v2}, {u1, v2}};
			int k = ((face.rotation / 90) % 4 + 4) % 4;
			float[] uv = new float[8];
			double cu = (u1 + u2) * 0.5;
			double cv = (v1 + v2) * 0.5;
			double cs = Math.cos(uvSpin);
			double sn = Math.sin(uvSpin);
			for (int i = 0; i < 4; i++) {
				double[] b = base[(i + 4 - k) % 4];
				double u = b[0];
				double v = b[1];
				if (uvSpin != 0.0) {
					double du = u - cu;
					double dv = v - cv;
					u = cu + du * cs - dv * sn;
					v = cv + du * sn + dv * cs;
				}
				uv[i * 2] = (float) u;
				uv[i * 2 + 1] = (float) v;
			}
			sink.quad(pos, uv, face.textureIndex);
		}
	}

	// ------------------------------------------------------------------ интерполяция ключей

	private static double[] eval(List<Key> ks, double t) {
		int n = ks.size();
		Key first = ks.get(0);
		if (t <= first.time) {
			return first.pre();
		}
		Key last = ks.get(n - 1);
		if (t >= last.time) {
			return last.post();
		}
		for (int i = 0; i < n - 1; i++) {
			Key a = ks.get(i);
			Key b = ks.get(i + 1);
			if (t < a.time || t >= b.time) {
				continue;
			}
			double[] va = a.post();
			double[] vb = b.pre();
			double span = b.time - a.time;
			double s = span <= 1e-9 ? 0.0 : (t - a.time) / span;

			// Для rotation — интерполируем по кратчайшей дуге, но с сохранением
			// направления, если разница ровно ±360 (полный оборот).
			if (a.isRotation || b.isRotation) {
				double[] out = new double[3];
				for (int c = 0; c < 3; c++) {
					double from = va[c];
					double to = vb[c];
					// Приводим "to" к значению, ближайшему к "from", но не теряя полные обороты.
					double delta = to - from;
					// Нормализуем в (-180, 180], но 360/‑360 оставляем как есть.
					if (delta > 180.0 || delta < -180.0) {
						double wrapped = ((delta + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
						// Если пользователь явно задал полный оборот (|delta| ~ 360), не сворачиваем.
						if (Math.abs(Math.abs(delta) - 360.0) > 1e-6) {
							delta = wrapped;
						}
					}
					if ("catmullrom".equals(a.interp) || "catmullrom".equals(b.interp)) {
						// Для Catmull-Rom берём контрольные точки и тоже приводим их к непрерывной шкале.
						double[] p0 = i > 0 ? ks.get(i - 1).post() : va;
						double[] p3 = i + 2 < n ? ks.get(i + 2).pre() : vb;
						double c0 = p0[c];
						double c3 = p3[c];
						// Выравниваем p0 и p3 относительно from/to.
						c0 = from + shortest(c0 - from);
						c3 = from + shortest(c3 - from);
						double s2 = s * s;
						double s3 = s2 * s;
						out[c] = 0.5 * ((2 * from) + (-c0 + to) * s
								+ (2 * c0 - 5 * from + 4 * to - c3) * s2
								+ (-c0 + 3 * from - 3 * to + c3) * s3);
					} else if ("step".equals(a.interp)) {
						out[c] = from;
					} else {
						out[c] = from + delta * s;
					}
				}
				return out;
			}

			if ("catmullrom".equals(a.interp) || "catmullrom".equals(b.interp)) {
				double[] p0 = i > 0 ? ks.get(i - 1).post() : va;
				double[] p3 = i + 2 < n ? ks.get(i + 2).pre() : vb;
				double[] out = new double[3];
				for (int c = 0; c < 3; c++) {
					double s2 = s * s;
					double s3 = s2 * s;
					out[c] = 0.5 * ((2 * va[c]) + (-p0[c] + vb[c]) * s
							+ (2 * p0[c] - 5 * va[c] + 4 * vb[c] - p3[c]) * s2
							+ (-p0[c] + 3 * va[c] - 3 * vb[c] + p3[c]) * s3);
				}
				return out;
			}
			if ("step".equals(a.interp)) {
				return va;
			}
			return new double[]{
					va[0] + (vb[0] - va[0]) * s,
					va[1] + (vb[1] - va[1]) * s,
					va[2] + (vb[2] - va[2]) * s
			};
		}
		return last.post();
	}

	/** Приводит угол к диапазону (-180, 180]. */
	private static double shortest(double deg) {
		double d = deg % 360.0;
		if (d > 180.0) d -= 360.0;
		if (d <= -180.0) d += 360.0;
		return d;
	}

	// ------------------------------------------------------------------ разбор JSON

	public static BbModel parse(Reader reader) {
		JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
		return new BbModel(root);
	}

	private BbModel(JsonObject root) {
		double rw = 16;
		double rh = 16;
		if (root.has("resolution") && root.get("resolution").isJsonObject()) {
			JsonObject r = root.getAsJsonObject("resolution");
			rw = num(r.get("width"), 16);
			rh = num(r.get("height"), 16);
		}
		this.resW = rw <= 0 ? 16 : rw;
		this.resH = rh <= 0 ? 16 : rh;

		List<TextureInfo> parsedTextures = new ArrayList<>();
		if (root.has("textures") && root.get("textures").isJsonArray()) {
			JsonArray ta = root.getAsJsonArray("textures");
			for (JsonElement te : ta) {
				if (!te.isJsonObject()) {
					parsedTextures.add(new TextureInfo("", (int) resW, (int) resH));
					continue;
				}
				JsonObject to = te.getAsJsonObject();
				String name = str(to.get("name"), "");
				int width = (int) num(to.get("width"), resW);
				int height = (int) num(to.get("height"), resH);
				parsedTextures.add(new TextureInfo(name, Math.max(1, width), Math.max(1, height)));
			}
		}
		this.textures = List.copyOf(parsedTextures);

		Map<String, Cube> cubes = new HashMap<>();
		if (root.has("elements") && root.get("elements").isJsonArray()) {
			for (JsonElement e : root.getAsJsonArray("elements")) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject o = e.getAsJsonObject();
				String type = str(o.get("type"), "cube");
				if (!"cube".equals(type)) {
					continue;
				}
				String uuid = str(o.get("uuid"), "");
				cubes.put(uuid, parseCube(o));
				cubeMeta.put(uuid, o);
			}
		}

		if (root.has("outliner") && root.get("outliner").isJsonArray()) {
			for (JsonElement e : root.getAsJsonArray("outliner")) {
				Node n = parseNode(e, cubes);
				if (n != null) {
					roots.add(n);
				}
			}
		}

		if (root.has("animations") && root.get("animations").isJsonArray()) {
			for (JsonElement e : root.getAsJsonArray("animations")) {
				if (e.isJsonObject()) {
					JsonObject o = e.getAsJsonObject();
					anims.put(str(o.get("name"), ""), parseAnim(o));
				}
			}
		}
		cubeMeta.clear();
		computeBounds();
	}

	private Cube parseCube(JsonObject o) {
		Cube c = new Cube();
		arr3(o.get("from"), c.from, 0);
		arr3(o.get("to"), c.to, 0);
		c.inflate = num(o.get("inflate"), 0);
		JsonObject faces = o.has("faces") && o.get("faces").isJsonObject() ? o.getAsJsonObject("faces") : new JsonObject();
		for (int i = 0; i < 6; i++) {
			Face f = new Face();
			JsonElement fe = faces.get(FACES[i]);
			if (fe != null && fe.isJsonObject()) {
				JsonObject fo = fe.getAsJsonObject();
				JsonElement tex = fo.get("texture");
				if (tex != null && tex.isJsonPrimitive()) {
					if (tex.getAsJsonPrimitive().isBoolean()) {
						if (!tex.getAsBoolean()) {
							f.visible = false;
						}
					} else if (tex.getAsJsonPrimitive().isNumber()) {
						f.textureIndex = tex.getAsInt();
					}
				}
				if (fo.has("uv") && fo.get("uv").isJsonArray() && fo.getAsJsonArray("uv").size() >= 4) {
					JsonArray uv = fo.getAsJsonArray("uv");
					f.u1 = num(uv.get(0), 0);
					f.v1 = num(uv.get(1), 0);
					f.u2 = num(uv.get(2), 0);
					f.v2 = num(uv.get(3), 0);
				}
				f.rotation = (int) num(fo.get("rotation"), 0);
			} else {
				f.visible = false;
			}
			if (f.textureIndex < 0 && !textures.isEmpty()) {
				f.textureIndex = 0;
			}
			c.faces[i] = f;
		}
		return c;
	}

	private Node parseNode(JsonElement e, Map<String, Cube> cubes) {
		if (e.isJsonPrimitive()) {
			String uuid = e.getAsString();
			Cube cube = cubes.get(uuid);
			JsonObject meta = cubeMeta.get(uuid);
			if (cube == null || meta == null) {
				return null;
			}
			if (meta.has("visibility") && meta.get("visibility").isJsonPrimitive() && !meta.get("visibility").getAsBoolean()) {
				return null;
			}
			Node n = new Node();
			n.uuid = uuid;
			n.cube = cube;
			arr3(meta.get("origin"), n.origin, 0);
			arr3(meta.get("rotation"), n.rotation, 0);
			return n;
		}
		if (!e.isJsonObject()) {
			return null;
		}
		JsonObject o = e.getAsJsonObject();
		if (o.has("visibility") && o.get("visibility").isJsonPrimitive() && !o.get("visibility").getAsBoolean()) {
			return null;
		}
		Node n = new Node();
		n.uuid = str(o.get("uuid"), "");
		arr3(o.get("origin"), n.origin, 0);
		arr3(o.get("rotation"), n.rotation, 0);
		if (o.has("children") && o.get("children").isJsonArray()) {
			for (JsonElement ce : o.getAsJsonArray("children")) {
				Node child = parseNode(ce, cubes);
				if (child != null) {
					n.children.add(child);
				}
			}
		}
		return n;
	}

	private Anim parseAnim(JsonObject o) {
		Anim a = new Anim();
		JsonElement loop = o.get("loop");
		if (loop != null && loop.isJsonPrimitive()) {
			if (loop.getAsJsonPrimitive().isBoolean()) {
				a.loop = loop.getAsBoolean() ? "loop" : "once";
			} else {
				a.loop = loop.getAsString();
			}
		}
		a.length = Math.max(0.0, num(o.get("length"), 0));
		if (o.has("animators") && o.get("animators").isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("animators").entrySet()) {
				if (!en.getValue().isJsonObject()) {
					continue;
				}
				JsonObject ao = en.getValue().getAsJsonObject();
				if (!ao.has("keyframes") || !ao.get("keyframes").isJsonArray()) {
					continue;
				}
				Animator an = new Animator();
				for (JsonElement ke : ao.getAsJsonArray("keyframes")) {
					if (!ke.isJsonObject()) {
						continue;
					}
					JsonObject ko = ke.getAsJsonObject();
					Key k = new Key();
					k.time = num(ko.get("time"), 0);
					k.interp = str(ko.get("interpolation"), "linear");
					if (ko.has("data_points") && ko.get("data_points").isJsonArray() && ko.getAsJsonArray("data_points").size() > 0) {
						JsonArray dps = ko.getAsJsonArray("data_points");
						k.pts = new double[dps.size()][3];
						for (int i = 0; i < dps.size(); i++) {
							JsonObject d = dps.get(i).isJsonObject() ? dps.get(i).getAsJsonObject() : new JsonObject();
							k.pts[i][0] = num(d.get("x"), 0);
							k.pts[i][1] = num(d.get("y"), 0);
							k.pts[i][2] = num(d.get("z"), 0);
						}
					}
					String ch = str(ko.get("channel"), "");
					switch (ch) {
						case "position" -> an.pos.add(k);
						case "rotation" -> {
							k.isRotation = true;
							an.rot.add(k);
						}
						case "scale" -> an.scl.add(k);
						default -> {
						}
					}
				}
				Comparator<Key> byTime = Comparator.comparingDouble(k -> k.time);
				an.pos.sort(byTime);
				an.rot.sort(byTime);
				an.scl.sort(byTime);
				if (!an.pos.isEmpty() || !an.rot.isEmpty() || !an.scl.isEmpty()) {
					a.animators.put(en.getKey(), an);
					a.hasKeys = true;
				}
			}
		}
		return a;
	}

	private void computeBounds() {
		double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		boolean[] any = {false};
		M id = M.translate(0, 0, 0);
		TexturedQuadSink sink = (pos, uv, textureIndex) -> {
			for (int i = 0; i < 4; i++) {
				for (int a = 0; a < 3; a++) {
					b[a] = Math.min(b[a], pos[i * 3 + a]);
					b[a + 3] = Math.max(b[a + 3], pos[i * 3 + a]);
				}
			}
			any[0] = true;
		};
		for (Node n : roots) {
			walk(n, id, null, 0.0, sink);
		}
		if (any[0]) {
			System.arraycopy(b, 0, bounds, 0, 6);
		}
	}

	// ------------------------------------------------------------------ утилиты JSON

	private static double num(JsonElement e, double def) {
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
			return def;
		}
		try {
			if (e.getAsJsonPrimitive().isNumber()) {
				return e.getAsDouble();
			}
			String s = e.getAsString().trim();
			return s.isEmpty() ? 0.0 : Double.parseDouble(s);
		} catch (NumberFormatException ex) {
			return 0.0;
		}
	}

	private static String str(JsonElement e, String def) {
		return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
	}

	private static void arr3(JsonElement e, double[] out, double def) {
		for (int i = 0; i < 3; i++) {
			out[i] = def;
		}
		if (e != null && e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			for (int i = 0; i < 3 && i < a.size(); i++) {
				out[i] = num(a.get(i), def);
			}
		}
	}

	// ------------------------------------------------------------------ матрицы 4x4 (аффинные, строки)

	private static final class M {
		final double[] m = new double[16];

		static M id() {
			M r = new M();
			r.m[0] = 1;
			r.m[5] = 1;
			r.m[10] = 1;
			r.m[15] = 1;
			return r;
		}

		static M translate(double x, double y, double z) {
			M r = id();
			r.m[3] = x;
			r.m[7] = y;
			r.m[11] = z;
			return r;
		}

		static M scale(double x, double y, double z) {
			M r = id();
			r.m[0] = x;
			r.m[5] = y;
			r.m[10] = z;
			return r;
		}

		static M rotX(double a) {
			M r = id();
			double c = Math.cos(a);
			double s = Math.sin(a);
			r.m[5] = c;
			r.m[6] = -s;
			r.m[9] = s;
			r.m[10] = c;
			return r;
		}

		static M rotY(double a) {
			M r = id();
			double c = Math.cos(a);
			double s = Math.sin(a);
			r.m[0] = c;
			r.m[2] = s;
			r.m[8] = -s;
			r.m[10] = c;
			return r;
		}

		static M rotZ(double a) {
			M r = id();
			double c = Math.cos(a);
			double s = Math.sin(a);
			r.m[0] = c;
			r.m[1] = -s;
			r.m[4] = s;
			r.m[5] = c;
			return r;
		}

		static M rotEuler(double rx, double ry, double rz) {
			return rotZ(rz).mul(rotY(ry)).mul(rotX(rx));
		}

		M mul(M o) {
			M r = new M();
			for (int i = 0; i < 4; i++) {
				for (int j = 0; j < 4; j++) {
					double s = 0;
					for (int k = 0; k < 4; k++) {
						s += m[i * 4 + k] * o.m[k * 4 + j];
					}
					r.m[i * 4 + j] = s;
				}
			}
			return r;
		}

		void apply(double x, double y, double z, double[] out) {
			out[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
			out[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
			out[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
		}
	}
}