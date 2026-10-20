import 'package:flutter/material.dart';
import 'model.dart';
import 'keyboard.dart';

class SettingsPage extends StatelessWidget {
  const SettingsPage({
    super.key,
    required this.model,
    required this.onBack,
    required this.onError,
    required this.onPrivacy,
  });
  final LikiModel model;
  final VoidCallback onBack, onPrivacy;
  final void Function(Object) onError;
  Future<void> save(String key, dynamic value) async {
    try {
      await model.setSetting(key, value);
    } catch (e) {
      onError(e);
    }
  }

  Widget section(String label) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 8),
    child: Text(
      label,
      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
    ),
  );
  Widget toggle(String key, String title, String detail) => SwitchListTile(
    contentPadding: EdgeInsets.zero,
    title: Text(title),
    subtitle: Text(detail),
    value: model.flag(key),
    onChanged: (v) => save(key, v),
  );
  Widget slider(
    String key,
    String title,
    double min,
    double max,
    int divisions,
    String suffix, [
    double? fallback,
  ]) {
    final value =
        (model.settingNumber(key) > 0
                ? model.settingNumber(key)
                : fallback ?? min)
            .clamp(min, max);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$title: ${value.toStringAsFixed(1)}$suffix'),
        Slider(
          value: value,
          min: min,
          max: max,
          divisions: divisions,
          label: value.toStringAsFixed(1),
          onChanged: (v) => save(key, v),
        ),
      ],
    );
  }

  Widget choice(String key, String title, List<String> values) =>
      DropdownButtonFormField<int>(
        initialValue: (model.settings[key] as num).toInt(),
        decoration: InputDecoration(labelText: title),
        isExpanded: true,
        items: List.generate(
          values.length,
          (i) => DropdownMenuItem(value: i, child: Text(values[i])),
        ),
        onChanged: (v) {
          if (v != null) save(key, v);
        },
      );
  @override
  Widget build(BuildContext context) {
    final pitch = model.geometry == null
        ? 6.0
        : model.geometry!.scale * model.density * 25.4 / model.xdpi;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton(onPressed: onBack, child: const Text('‹  操作画面に戻る')),
        ),
        const Text(
          '設定',
          style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
        ),
        const Text('操作方法を切り替えます。変更はすぐに保存されます。', style: TextStyle(color: muted)),
        TextButton(onPressed: onPrivacy, child: const Text('プライバシーポリシー')),
        section('表示'),
        choice('screen_orientation', '画面の向き', [
          '自動（端末の設定に従う）',
          '縦向き',
          '横向き',
          '横向き（反対向き）',
        ]),
        toggle(
          'fullscreen_controls',
          '全画面表示',
          '接続後の操作画面でシステムバーを隠す。画面端からスワイプすると一時表示',
        ),
        toggle('keep_screen_on', '画面を常にON', 'Likiを開いている間は自動消灯しない'),
        section('操作感'),
        toggle('touch_vibration', 'タッチ時に振動', 'キーのタップとトラックパッドのクリック時に振動'),
        section('キーボード'),
        const Text('レイヤーごとのボタン枠の色'),
        for (var layer = 0; layer < 4; layer++)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: CircleAvatar(
              radius: 9,
              backgroundColor: Color(
                (model.settings['border_colors'] as List)[layer],
              ),
            ),
            title: Text(
              'レイヤー $layer の枠色  #${(((model.settings['border_colors'] as List)[layer] as int) & 0xffffff).toRadixString(16).padLeft(6, '0').toUpperCase()}',
            ),
            onTap: () => borderColor(context, layer),
          ),
        slider('key_pitch_mm', 'キーピッチ（概算）', 3, 24, 210, ' mm', pitch),
        const Text(
          '端末の画面密度から換算した概算値です。画面に収めるにはメニューの「表示をリセット」を押します。',
          style: TextStyle(color: muted),
        ),
        toggle(
          'kle_json_key_labels',
          'KLE JSONファイルに従った表示',
          'オン: KLEの文字を表示し、空欄ならキー割り当て名を表示。オフ: 有効なキー割り当て名を表示',
        ),
        toggle('sticky_modifiers', '修飾キーを保持', 'Ctrl・Shiftなどを次のキー入力まで保持'),
        toggle('pinch_zoom', 'ピンチで拡大縮小', '2本指でキーボードの表示倍率を変更'),
        toggle('drag_move_keyboard', 'ドラッグでキーボードを移動', '通常キーの上を1本指で動かして表示位置を変更'),
        section('トラックパッド'),
        slider('pointer_speed', 'ポインターの速度', .5, 3, 25, '倍'),
        choice('pointer_send_mode', 'カーソルの送信間隔', [
          '即時（毎回送信）',
          '16ms（送信多め）',
          '32ms（標準）',
        ]),
        const Text(
          '同じ速さのまま、カーソル移動の送信頻度を変えられます。',
          style: TextStyle(color: muted),
        ),
        toggle('tap_to_click', 'タップでクリック', '1本指でタップして左クリック'),
        toggle('tap_drag', 'ダブルタップでドラッグ', 'タップでクリックを有効にして、2回目のタップ後に指を動かす'),
        toggle('hold_to_drag', '長押しでドラッグ', '1本指で押し続けてドラッグ'),
        toggle('two_finger_right_click', '2本指タップで右クリック', '動かさずに2本指でタップ'),
        toggle('two_finger_scroll', '2本指でスクロール', 'トラックパッドを2本指で上下に動かす'),
        toggle('trackpad_pinch_zoom', 'ピンチでズーム', '対応するPC画面へCtrl＋ホイールとして送信'),
        toggle('three_finger_middle_click', '3本指タップで中クリック', '動かさずに3本指でタップ'),
        slider('scroll_speed', 'スクロール速度', .5, 3, 25, '倍'),
        toggle('reverse_scroll', 'スクロール方向を反転', '2本指で動かしたときのスクロール方向を逆にする'),
      ],
    );
  }

  Future<void> borderColor(BuildContext context, int layer) async {
    const colors = {
      'グレー': 0x657789,
      '青': 0x71a9e8,
      '緑': 0x80cbc4,
      '黄': 0xedb369,
      '赤': 0xe87c73,
      '紫': 0xbe9ce7,
    };
    final selected = await showDialog<int>(
      context: context,
      builder: (c) => SimpleDialog(
        title: Text('レイヤー $layer の枠色'),
        children: [
          for (final entry in colors.entries)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(c, entry.value),
              child: Text(
                '${entry.key}  #${entry.value.toRadixString(16).toUpperCase()}',
              ),
            ),
          SimpleDialogOption(
            onPressed: () => Navigator.pop(c, -1),
            child: const Text('カスタム色を入力…'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(c),
            child: const Text('閉じる'),
          ),
        ],
      ),
    );
    if (selected == null) return;
    if (selected >= 0) {
      await save('layer_border_color_$layer', selected | 0xff000000);
      return;
    }
    if (!context.mounted) return;
    final color = (model.settings['border_colors'] as List)[layer] as int;
    var value = '#${(color & 0xffffff).toRadixString(16).padLeft(6, '0')}';
    final custom = await showDialog<int>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, update) {
          final valid = RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(value.trim());
          return AlertDialog(
            title: Text('レイヤー $layer の枠色（#RRGGBB）'),
            content: TextFormField(
              initialValue: value,
              autofocus: true,
              onChanged: (text) => update(() => value = text),
              decoration: InputDecoration(
                errorText: valid ? null : '#RRGGBB形式で入力してください',
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(c),
                child: const Text('キャンセル'),
              ),
              TextButton(
                onPressed: valid
                    ? () => Navigator.pop(
                        c,
                        int.parse(value.trim().substring(1), radix: 16),
                      )
                    : null,
                child: const Text('保存'),
              ),
            ],
          );
        },
      ),
    );
    if (custom != null)
      await save('layer_border_color_$layer', custom | 0xff000000);
  }
}
