import 'dart:async';
import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';
import 'model.dart';

/// Main's thresholds and state transitions, in Flutter logical pixels.
class TrackpadGesture {
  TrackpadGesture(this.model);
  final LikiModel model;
  Timer? hold;
  Offset last = Offset.zero,
      down = Offset.zero,
      remainder = Offset.zero,
      lastTap = Offset.zero;
  double scrollRemainder = 0, zoomRemainder = 0, startSpan = 0, lastSpan = 0;
  Offset startCenter = Offset.zero;
  int downTime = 0, lastTapTime = 0, maxPointers = 1, gesture = 0;
  bool moved = false,
      dragging = false,
      scrolling = false,
      tapDragArmed = false,
      multiMoved = false;
  final Map<int, Offset> starts = {};
  void send(String method, [Json? args]) {
    unawaited(model.invoke(method, args));
  }

  void click(int button) => send('click', {'button': button});
  void touch(PointerEvent event, Map<int, Offset> points) {
    final position = event.localPosition, time = event.timeStamp.inMilliseconds;
    if (event is PointerDownEvent && points.length == 1) {
      last = down = position;
      downTime = time;
      tapDragArmed =
          model.flag('tap_drag') &&
          model.flag('tap_to_click') &&
          lastTapTime > 0 &&
          time - lastTapTime >= 1 &&
          time - lastTapTime <= 350 &&
          (down - lastTap).distance <= 24;
      remainder = Offset.zero;
      scrollRemainder = 0;
      moved = dragging = scrolling = multiMoved = false;
      maxPointers = 1;
      starts.clear();
      gesture = 0;
      zoomRemainder = 0;
      if (model.flag('hold_to_drag')) {
        hold = Timer(const Duration(milliseconds: 450), () {
          if (model.flag('hold_to_drag') && !moved && !scrolling) {
            dragging = true;
            send('mouseDown', {'button': 1});
          }
        });
      }
    } else if (event is PointerDownEvent) {
      hold?.cancel();
      final wasDragging = dragging;
      if (dragging) {
        send('mouseUp', {'button': 1});
        dragging = false;
      }
      if (!scrolling) {
        starts.clear();
        starts.addAll(points);
      } else {
        starts[event.pointer] = position;
      }
      scrolling = true;
      multiMoved = multiMoved || wasDragging;
      if (points.length > maxPointers) maxPointers = points.length;
      tapDragArmed = false;
      lastTapTime = 0;
      last = center(points);
      if (points.length == 2) {
        startSpan = lastSpan = span(points);
        startCenter = last;
        gesture = 0;
      }
    } else if (event is PointerMoveEvent) {
      if (points.length >= 2) {
        updateMulti(points);
        if (points.length == 2 && maxPointers == 2 && multiMoved) {
          final c = center(points), currentSpan = span(points);
          if (gesture == 0) {
            final spanChange = (currentSpan - startSpan).abs(),
                travel = (c - startCenter).distance;
            if (model.flag('trackpad_pinch_zoom') &&
                spanChange > 12 &&
                spanChange > travel * 1.2) {
              gesture = 2;
            } else if (travel > 8 &&
                (travel > spanChange * .8 ||
                    !model.flag('trackpad_pinch_zoom'))) {
              gesture = 1;
            }
          }
          if (gesture == 1) {
            final direction = model.flag('reverse_scroll') ? -1 : 1;
            final delta =
                (last.dy - c.dy) /
                    18 *
                    model.settingNumber('scroll_speed') *
                    direction +
                scrollRemainder;
            final ticks = delta.truncate();
            scrollRemainder = delta - ticks;
            if (ticks != 0 && model.flag('two_finger_scroll'))
              send('scroll', {'amount': ticks});
            last = c;
          } else if (gesture == 2) {
            final delta = (currentSpan - lastSpan) / 24 + zoomRemainder;
            final ticks = delta.truncate();
            zoomRemainder = delta - ticks;
            if (ticks != 0) send('zoom', {'amount': ticks});
            lastSpan = currentSpan;
          }
        }
      } else if (!scrolling) {
        if ((position - down).distance > 8) {
          moved = true;
          hold?.cancel();
          if (tapDragArmed && !dragging) {
            dragging = true;
            send('mouseDown', {'button': 1});
            lastTapTime = 0;
          }
        }
        if (tapDragArmed && !dragging && !moved) return;
        final delta =
            (position - last) *
                (1.45 * model.settingNumber('pointer_speed') * model.density) +
            remainder;
        final ix = delta.dx.truncate(), iy = delta.dy.truncate();
        remainder = delta - Offset(ix.toDouble(), iy.toDouble());
        if (ix != 0 || iy != 0) send('move', {'x': ix, 'y': iy});
        last = position;
      }
    } else if (event is PointerUpEvent || event is PointerCancelEvent) {
      if (points.length > 1 && event is PointerUpEvent) {
        updateMulti(points);
        if (points.length == 2)
          last = points.entries.firstWhere((e) => e.key != event.pointer).value;
        return;
      }
      hold?.cancel();
      if (scrolling) updateMulti(points);
      if (dragging) {
        send('mouseUp', {'button': 1});
      } else if (event is PointerUpEvent &&
          scrolling &&
          !multiMoved &&
          time - downTime < 450) {
        if (maxPointers == 2 && model.flag('two_finger_right_click')) click(2);
        if (maxPointers == 3 && model.flag('three_finger_middle_click'))
          click(4);
      } else if (event is PointerUpEvent &&
          model.flag('tap_to_click') &&
          !moved &&
          !scrolling &&
          time - downTime < 450) {
        click(1);
        lastTapTime = time;
        lastTap = position;
      }
      if (dragging || scrolling || moved || event is PointerCancelEvent)
        lastTapTime = 0;
      dragging = scrolling = tapDragArmed = false;
      gesture = 0;
      starts.clear();
    }
  }

  void updateMulti(Map<int, Offset> points) {
    for (final e in points.entries) {
      if (starts.containsKey(e.key) && (e.value - starts[e.key]!).distance > 8)
        multiMoved = true;
    }
  }

  Offset center(Map<int, Offset> points) {
    final values = points.values.take(2).toList();
    return (values[0] + values[1]) / 2;
  }

  double span(Map<int, Offset> points) {
    final values = points.values.take(2).toList();
    return (values[0] - values[1]).distance;
  }

  void cancel() {
    hold?.cancel();
    if (dragging) send('mouseUp', {'button': 1});
    dragging = scrolling = tapDragArmed = false;
    gesture = 0;
    lastTapTime = 0;
    starts.clear();
  }
}
