import React, { useEffect, useMemo } from 'react';
import { AppState, View } from 'react-native';
import type { GestureResponderEvent } from 'react-native';
import type { LikiModel } from './model';
import { TrackpadGesture } from './gestures';
import type { Touch } from './gestures';
import { colors } from './Keyboard';

export function TrackpadPanel({ model, active }: { model: LikiModel; active: boolean }) {
  const gesture = useMemo(() => new TrackpadGesture(model), [model]);
  useEffect(() => {
    const listener = AppState.addEventListener('change', state => { if (state !== 'active') gesture.cancel(); });
    return () => { listener.remove(); gesture.cancel(); };
  }, [gesture]);
  useEffect(() => { if (!active) gesture.cancel(); }, [active, gesture]);
  const touch = (kind: Touch['kind'], event: GestureResponderEvent) => {
    if (!active) return;
    const { changedTouches, touches } = event.nativeEvent;
    if (kind === 'move' || kind === 'up') for (const point of touches)
      gesture.points.set(point.identifier, { x: point.locationX, y: point.locationY });
    for (const point of changedTouches) gesture.touch({ kind, id: point.identifier,
      position: { x: point.locationX, y: point.locationY }, time: point.timestamp });
  };
  return <View accessibilityLabel="トラックパッド" style={{ flex: 1, margin: 8, borderRadius: 12,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.muted }}
    onStartShouldSetResponder={() => active} onMoveShouldSetResponder={() => active} onResponderTerminationRequest={() => false}
    onTouchStart={event => touch('down', event)} onTouchMove={event => touch('move', event)}
    onTouchEnd={event => touch('up', event)} onTouchCancel={() => gesture.cancel()} />;
}
