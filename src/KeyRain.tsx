import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Animated, Easing, Platform, Text, View } from 'react-native';
import type { LikiModel } from './model';
import { usageLabel } from './zmk';

type Drop = { id: number; label: string; column: number; duration: number };
const green = '#58ff83';
const fontFamily = Platform.OS === 'ios' ? 'Menlo' : 'monospace';

function FallingKey({ drop, height, columns, done }: { drop: Drop; height: number; columns: number; done(id: number): void }) {
  const [fall] = useState(() => new Animated.Value(0)), [sparkle] = useState(() => new Animated.Value(.65));
  useEffect(() => {
    const movement = Animated.timing(fall, { toValue: 1, duration: drop.duration, easing: Easing.linear, useNativeDriver: true });
    const flicker = Animated.loop(Animated.sequence([
      Animated.timing(sparkle, { toValue: 1, duration: 100, useNativeDriver: true }),
      Animated.timing(sparkle, { toValue: .65, duration: 160, useNativeDriver: true }),
    ]));
    flicker.start(); movement.start(({ finished }) => { if (finished) done(drop.id); });
    return () => { movement.stop(); flicker.stop(); };
  }, [done, drop.duration, drop.id, fall, sparkle]);
  const fontSize = Math.max(12, Math.min(30, 70 / drop.label.length));
  return <Animated.View style={{ position: 'absolute', top: -264, left: `${(drop.column + .5) / columns * 100}%`,
    width: 104, marginLeft: -52, alignItems: 'center',
    opacity: fall.interpolate({ inputRange: [0, .75, 1], outputRange: [1, 1, 0] }),
    transform: [{ translateY: fall.interpolate({ inputRange: [0, 1], outputRange: [0, height + 264] }) }] }}>
    {Array.from({ length: 7 }, (_, index) => <Text key={index} style={{ fontFamily, fontSize, lineHeight: 30,
      color: green, opacity: .04 + index * .065, textShadowColor: green, textShadowRadius: 5 }}>{drop.label}</Text>)}
    <Animated.Text style={{ fontFamily, fontSize: fontSize + 3, lineHeight: 40, fontWeight: 'bold', opacity: sparkle,
      color: '#e3ffe9', textShadowColor: green, textShadowRadius: 16 }}>{drop.label}</Animated.Text>
  </Animated.View>;
}

export function KeyRain({ model }: { model: LikiModel }) {
  const [drops, setDrops] = useState<Drop[]>([]), [viewport, setViewport] = useState({ width: 0, height: 0 });
  const serial = useRef(0), columns = Math.max(4, Math.floor(viewport.width / 56));
  const done = useCallback((id: number) => setDrops(current => current.filter(drop => drop.id !== id)), []);
  useEffect(() => {
    let pressed = new Set<number>(), consumer = 0;
    const listener = model.native.subscribe(event => {
      if (!event.relayInput) return;
      const input = event.relayInput, next = new Set<number>(input.pressed);
      const usages = input.pressed.filter((code: number) => !pressed.has(code)).map((code: number) => 0x70000 | code);
      if (input.consumer && input.consumer !== consumer) usages.push(0xc0000 | input.consumer);
      pressed = next; consumer = input.consumer;
      if (model.relayPaused || !usages.length) return;
      const additions = usages.map((usage: number) => {
        const id = ++serial.current;
        return { id, label: usageLabel(usage), column: Math.floor((id * .61803398875 % 1) * columns), duration: 1400 + Math.random() * 1000 };
      });
      setDrops(current => [...current, ...additions].slice(-32));
    });
    return () => listener.remove();
  }, [model, columns]);
  return <View pointerEvents="none" accessibilityLabel="受信したキーの表示" style={{ flex: 1, overflow: 'hidden', backgroundColor: '#060d09' }}
    onLayout={event => setViewport({ width: event.nativeEvent.layout.width, height: event.nativeEvent.layout.height })}>
    {Array.from({ length: columns }, (_, column) => <View key={column} style={{ position: 'absolute', top: 0, bottom: 0,
      left: `${(column + .5) / columns * 100}%`, width: 1, backgroundColor: green, opacity: .025 }} />)}
    {drops.map(drop => <FallingKey key={drop.id} drop={drop} height={viewport.height} columns={columns} done={done} />)}
  </View>;
}
