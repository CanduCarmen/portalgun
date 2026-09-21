#!/usr/bin/env bash
#
# enable_animations.sh
#
# Включает анимации из portal.bbmodel вместо процедурных заглушек:
#   - PortalRenderer.collect : scale=1.0, spin=0.0, слои только из keyframes
#   - PortalManager.tick     : убирает вызов drawParticles
#   - Portal.java            : удаляет метод scale()
#
# Безопасно запускать повторно: уже применённые правки не дублируются.
# Перед изменениями каждый файл копируется в <file>.bak
#
# Использование:
#   ./enable_animations.sh [путь-к-корню-проекта]
# По умолчанию корень = текущая директория.
#

set -euo pipefail

ROOT="${1:-.}"
cd "$ROOT"

SRC_RENDERER="src/main/java/dev/portalgun/PortalRenderer.java"
SRC_MANAGER="src/main/java/dev/portalgun/PortalManager.java"
SRC_PORTAL="src/main/java/dev/portalgun/Portal.java"

for f in "$SRC_RENDERER" "$SRC_MANAGER" "$SRC_PORTAL"; do
	if [[ ! -f "$f" ]]; then
		echo "!! Не найден файл: $f" >&2
		echo "   Укажи корень проекта: $0 /путь/к/проекту" >&2
		exit 1
	fi
done

backup() {
	local f="$1"
	if [[ ! -f "${f}.bak" ]]; then
		cp -p "$f" "${f}.bak"
		echo "  -> бэкап: ${f}.bak"
	fi
}

# Заменить блок между маркером-началом и маркером-концом на новый текст.
# Аргументы: file, start_regex, end_regex, replacement_file
replace_block() {
	local file="$1" start_re="$2" end_re="$3" repl="$4"
	python3 - "$file" "$start_re" "$end_re" "$repl" <<'PY'
import re, sys
path, start_re, end_re, repl_path = sys.argv[1:5]
with open(path, "r", encoding="utf-8") as f:
    src = f.read()
with open(repl_path, "r", encoding="utf-8") as f:
    repl = f.read()
# ищем от строки, матчащей start_re, до строки, матчащей end_re (включительно)
pat = re.compile(
    r"(?ms)^([ \t]*)" + start_re + r".*?^[ \t]*" + end_re + r"[^\n]*\n"
)
m = pat.search(src)
if not m:
    print(f"  !! не найден блок {start_re}..{end_re} в {path}", file=sys.stderr)
    sys.exit(2)
indent = m.group(1)
# переотступляем replacement под найденный отступ
lines = repl.splitlines(keepends=True)
out = "".join((indent + ln if ln.strip() else ln) for ln in lines)
src = src[:m.start()] + out + src[m.end():]
with open(path, "w", encoding="utf-8") as f:
    f.write(src)
print(f"  -> заменён блок в {path}")
PY
}

echo "== 1/3 PortalRenderer.java =="
backup "$SRC_RENDERER"

# --- новый collect(...) ---
TMP_RENDERER="$(mktemp)"
cat > "$TMP_RENDERER" <<'JAVA'
private static void collect(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cam) {
	BbModel model = PortalModels.portal();
	if (model == null) {
		return;
	}
	List<Portal> portals = PortalManager.snapshot();
	if (portals.isEmpty()) {
		return;
	}
	RenderType type = RenderTypes.entityTranslucent(TEXTURE);
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

		// Процедурные заглушки отключены: только анимации из .bbmodel.
		final double scale = 1.0;
		final double spin = 0.0;
		final int alpha = 255;

		poseStack.pushPose();
		poseStack.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
		collector.submitCustomGeometry(poseStack, type, (pose, buffer) ->
				model.emit(p.yawRad, scale, spin, layers,
						(pos, uv) -> quad(pose, buffer, pos, uv, alpha)));
		poseStack.popPose();
	}
}
JAVA

if grep -q "Процедурные заглушки отключены" "$SRC_RENDERER"; then
	echo "  = уже пропатчен, пропускаю"
else
	# сигнатура метода collect и его закрывающая скобка на отдельной строке
	replace_block "$SRC_RENDERER" \
		"private static void collect\(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cam\) \{" \
		"\}" \
		"$TMP_RENDERER" || true
	# убираем ненужную константу SPIN_PERIOD, если осталась
	sed -i.tmp -E '/private static final double SPIN_PERIOD/d' "$SRC_RENDERER" && rm -f "$SRC_RENDERER.tmp"
fi
rm -f "$TMP_RENDERER"

echo "== 2/3 PortalManager.java =="
backup "$SRC_MANAGER"

if grep -q "drawParticles" "$SRC_MANAGER"; then
	# удаляем блок if (PortalModels.portal() == null) { p.drawParticles(mc.level); }
	python3 - "$SRC_MANAGER" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
pat = re.compile(
    r"(?ms)^[ \t]*if \(PortalModels\.portal\(\) == null\) \{\s*"
    r"p\.drawParticles\(mc\.level\);[^\n]*\n[ \t]*\}\s*\n"
)
s2, n = pat.subn("", s)
open(p, "w", encoding="utf-8").write(s2)
print(f"  -> удалено drawParticles-блоков: {n}")
PY
else
	echo "  = уже пропатчен, пропускаю"
fi

echo "== 3/3 Portal.java =="
backup "$SRC_PORTAL"

if grep -qE "public double scale\(\)" "$SRC_PORTAL"; then
	python3 - "$SRC_PORTAL" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
# удаляем Javadoc + метод scale() {...} целиком
pat = re.compile(
    r"(?ms)^[ \t]*/\*\*.*?\*/\s*"           # javadoc
    r"^[ \t]*public double scale\(\) \{.*?^[ \t]*\}\s*\n"
)
s2, n = pat.subn("", s)
if n == 0:
    # javadoc мог быть однострочным или отсутствовать — режем просто тело
    pat2 = re.compile(r"(?ms)^[ \t]*public double scale\(\) \{.*?^[ \t]*\}\s*\n")
    s2, n = pat2.subn("", s)
open(p, "w", encoding="utf-8").write(s2)
print(f"  -> удалено scale(): {n}")
PY
else
	echo "  = уже пропатчен, пропускаю"
fi

echo
echo "Готово. Проверь git diff и пересобери проект:"
echo "  ./gradlew build"
echo
echo "Не забудь: в portal.bbmodel у анимаций open/close/whirlpool должны быть"
echo "length > 0, animators с uuid куба и keyframes. Иначе hasKeyframes(...) == false."
