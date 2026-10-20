import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'model.dart';
import 'trackpad.dart';

const background = Color(0xff10151c), surface = Color(0xff1f2834);
const foreground = Color(0xffebf2f7),
    muted = Color(0xffa3b3c0),
    accent = Color(0xff80cbc4);

class Keyboard extends StatefulWidget {
  const Keyboard({
    super.key,
    required this.model,
    required this.onEdit,
    required this.onError,
  });
  final LikiModel model;
  final void Function(KeyShape) onEdit;
  final void Function(Object) onError;
  @override
  State<Keyboard> createState() => _KeyboardState();
}

class _Chord {
  _Chord(this.key, this.layer, this.down);
  final KeyShape key;
  final int layer;
  final Offset down;
  bool moved = false;
}

class _KeyboardState extends State<Keyboard> {
  final Map<int, Offset> points = {};
  final Map<int, _Chord> chords = {};
  late final TrackpadGesture trackpad = TrackpadGesture(widget.model);
  KeyShape? selected;
  Offset down = Offset.zero, last = Offset.zero;
  bool moved = false,
      pinching = false,
      trackpadTouch = false,
      momentary = false;
  int? momentaryPointer;
  double pinchSpan = 0;
  LikiModel get model => widget.model;
  KeyboardGeometry get geometry => model.geometry!;
  void feedback() {
    unawaited(model.invoke('vibrate'));
  }

  void tap(KeyShape key, int layer) {
    feedback();
    if (model.editing) {
      if (!key.ghost) widget.onEdit(key);
    } else {
      unawaited(model.tap(key, layer).catchError(widget.onError));
    }
  }

  void pointer(PointerEvent event) {
    if (event is PointerDownEvent ||
        event is PointerMoveEvent ||
        event is PointerUpEvent)
      points[event.pointer] = event.localPosition;
    final first = event is PointerDownEvent && points.length == 1;
    if (first) {
      down = last = event.localPosition;
      moved = pinching = false;
      selected = geometry.hit(down);
      trackpadTouch = selected?.ghost == true && !model.editing;
      if (trackpadTouch) {
        trackpad.touch(event, points);
        return;
      }
      if (!model.editing &&
          selected != null &&
          model.bindingFor(selected!)?.action == 'MOMENTARY') {
        momentary = true;
        momentaryPointer = event.pointer;
        model.momentaryDown(selected!);
        feedback();
      }
      return;
    }
    if (momentary) {
      if (event is PointerDownEvent) {
        final key = geometry.hit(event.localPosition);
        if (key != null &&
            !key.ghost &&
            model.bindingFor(key)?.action != 'MOMENTARY')
          chords[event.pointer] = _Chord(key, model.layer, event.localPosition);
      } else if (event is PointerMoveEvent) {
        final touch = chords[event.pointer];
        if (touch != null && (event.localPosition - touch.down).distance > 8)
          touch.moved = true;
      } else if (event is PointerUpEvent) {
        if (event.pointer == momentaryPointer) {
          model.momentaryUp();
          momentaryPointer = null;
        } else {
          final touch = chords.remove(event.pointer);
          if (touch != null && !touch.moved) tap(touch.key, touch.layer);
        }
        if (points.length == 1) {
          model.momentaryUp();
          momentary = false;
          chords.clear();
          selected = null;
        }
      } else if (event is PointerCancelEvent) {
        cancel();
      }
    } else if (trackpadTouch) {
      trackpad.touch(event, points);
      if ((event is PointerUpEvent && points.length == 1) ||
          event is PointerCancelEvent)
        trackpadTouch = false;
    } else if (event is PointerDownEvent && points.length == 2) {
      pinching = true;
      pinchSpan = span();
    } else if (event is PointerMoveEvent) {
      if (points.length >= 2) {
        final next = span(), pair = points.values.take(2).toList();
        if (model.flag('pinch_zoom') && pinchSpan > 0)
          geometry.zoomAt(
            next / pinchSpan,
            (pair[0] + pair[1]) / 2,
            model.settingNumber('key_pitch_mm') > 0,
          );
        pinchSpan = next;
      } else if (!pinching) {
        if ((event.localPosition - down).distance > 8) moved = true;
        if (moved && model.flag('drag_move_keyboard'))
          geometry.pan += event.localPosition - last;
        last = event.localPosition;
      }
      setState(() {});
    } else if (event is PointerUpEvent && points.length == 1) {
      if (!moved && !pinching && selected != null)
        tap(selected!, model.displayedLayer);
      selected = null;
    }
    if (event is PointerUpEvent) {
      points.remove(event.pointer);
      model.saveSession();
    }
    if (event is PointerCancelEvent) cancel();
  }

  double span() {
    final p = points.values.take(2).toList();
    return (p[0] - p[1]).distance;
  }

  void cancel({bool notify = true}) {
    trackpad.cancel();
    model.momentaryUp(notify: notify);
    points.clear();
    chords.clear();
    momentary = trackpadTouch = false;
    momentaryPointer = null;
    selected = null;
  }

  @override
  void dispose() {
    cancel(notify: false);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final g = geometry;
      g.size = Size(constraints.maxWidth, constraints.maxHeight);
      final pitch = model.settingNumber('key_pitch_mm'),
          saved = model.settingNumber('auto_key_scale_px');
      g.base = pitch > 0
          ? pitch * model.xdpi / 25.4 / model.density
          : saved > 0
          ? saved / model.density
          : math.min(
              (g.size.width - 20) / math.max(1, g.bounds.width),
              (g.size.height - 20) / math.max(1, g.bounds.height),
            );
      if (pitch <= 0 && saved <= 0 && g.base > 0) {
        // Persist physical pixels, as the Android version does, without notifying during build.
        model.settings['auto_key_scale_px'] = g.base * model.density;
        unawaited(
          model.invoke('setting', {
            'key': 'auto_key_scale_px',
            'value': g.base * model.density,
          }),
        );
      }
      return Semantics(
        label: 'KLEキーボード。通常はタップで入力、設定モードではタップで割り当て',
        child: Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: pointer,
          onPointerMove: pointer,
          onPointerUp: pointer,
          onPointerCancel: pointer,
          child: CustomPaint(size: g.size, painter: KeyboardPainter(model)),
        ),
      );
    },
  );
}

class KeyboardPainter extends CustomPainter {
  KeyboardPainter(this.model);
  final LikiModel model;
  @override
  void paint(Canvas canvas, Size size) {
    final g = model.geometry!;
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    canvas.translate(g.origin.dx, g.origin.dy);
    canvas.scale(g.scale);
    for (final key in model.keys) {
      canvas.save();
      canvas.translate(key.rx, key.ry);
      canvas.rotate(key.rotation * math.pi / 180);
      canvas.translate(-key.rx, -key.ry);
      final binding = model.bindingFor(key), overridden = model.overridden(key);
      final active =
          !key.ghost &&
          !model.editing &&
          (model.layerKey == key.index ||
              binding != null &&
                  binding.code == 0 &&
                  binding.modifier != 0 &&
                  model.modifiers & binding.modifier != 0);
      final fill = active
          ? const Color(0xff24736d)
          : model.displayedLayer != 0 && overridden && !key.ghost
          ? const Color(0xff26485b)
          : Color.fromARGB(
              255,
              20 + ((key.color.toARGB32() >> 16) & 255) ~/ 4,
              25 + ((key.color.toARGB32() >> 8) & 255) ~/ 4,
              32 + (key.color.toARGB32() & 255) ~/ 4,
            );
      if (!key.decal) {
        for (final rect in [
          key.primary,
          if (key.primary != key.secondary) key.secondary,
        ]) {
          final rounded = RRect.fromRectAndRadius(
            rect.deflate(.025),
            const Radius.circular(.1),
          );
          canvas.drawRRect(rounded, Paint()..color = fill);
          canvas.drawRRect(
            rounded,
            Paint()
              ..style = PaintingStyle.stroke
              ..strokeWidth = .018
              ..color = Color(
                (model.settings['border_colors'] as List)[model.displayedLayer],
              ),
          );
        }
      }
      final showBinding =
          !key.ghost &&
          (!model.flag('kle_json_key_labels') ||
              key.labels.every((s) => s.isEmpty));
      if (!showBinding) {
        for (var i = 0; i < 9; i++) {
          final label = key.labels[i];
          if (label.isEmpty) continue;
          final col = i % 3, row = i ~/ 3;
          final x = col == 0
              ? key.x + .11
              : col == 2
              ? key.x + key.w - .11
              : key.x + key.w / 2;
          final y = row == 0
              ? key.y + .3
              : row == 1
              ? key.y + key.h / 2 + .08
              : key.y + key.h - .1;
          text(
            canvas,
            label,
            Offset(x, y),
            math.min(
              .25,
              math.max(.14, (key.w - .18) / math.max(2, label.length * .54)),
            ),
            col,
          );
        }
      } else if (binding != null) {
        text(
          canvas,
          binding.name,
          Offset(key.x + key.w / 2, key.y + key.h / 2 + .08),
          math.min(
            .28,
            math.max(
              .12,
              (key.w - .14) / math.max(2, binding.name.length * .55),
            ),
          ),
          1,
        );
        if (model.displayedLayer != 0 && !overridden)
          text(
            canvas,
            '継承',
            Offset(key.x + key.w / 2, key.y + key.h - .1),
            .13,
            1,
            muted,
          );
      }
      canvas.restore();
    }
    canvas.restore();
  }

  void text(
    Canvas canvas,
    String value,
    Offset baseline,
    double fontSize,
    int align, [
    Color color = foreground,
  ]) {
    final scale = model.geometry!.scale;
    final painter = TextPainter(
      text: TextSpan(
        text: value,
        style: TextStyle(
          color: color,
          fontWeight: FontWeight.bold,
          fontSize: fontSize * scale,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    final x =
        baseline.dx * scale -
        (align == 0
            ? 0
            : align == 2
            ? painter.width
            : painter.width / 2);
    final y =
        baseline.dy * scale -
        painter.computeDistanceToActualBaseline(TextBaseline.alphabetic);
    canvas.save();
    canvas.scale(1 / scale);
    painter.paint(canvas, Offset(x, y));
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant KeyboardPainter oldDelegate) => true;
}
