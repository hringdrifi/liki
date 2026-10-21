using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

namespace LikiReconnect {
    internal static class Program {
        [STAThread]
        private static void Main() {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            bool first;
            using (var mutex = new Mutex(true, @"Local\LikiReconnect", out first))
            using (var open = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\LikiReconnect.Open")) {
                if (!first) { open.Set(); return; }
                try {
                    string path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                        "LikiReconnect", "devices.json");
                    using (var form = new MainForm(new SettingsStore(path))) {
                        var wait = ThreadPool.RegisterWaitForSingleObject(open, (state, timeout) => {
                            if (form.IsHandleCreated && !form.IsDisposed) {
                                try { form.BeginInvoke(new Action(form.ShowWindow)); }
                                catch (InvalidOperationException) { }
                            }
                        }, null, Timeout.Infinite, false);
                        try { Application.Run(form); }
                        finally { wait.Unregister(null); }
                    }
                } catch (Exception error) {
                    MessageBox.Show("起動できませんでした。登録ファイルは上書きしていません。\n" + error.Message,
                        "Liki 再接続サポート", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
        }
    }

    internal sealed class MainForm : Form {
        private readonly SettingsStore store;
        private readonly Dictionary<string, RegisteredDevice> registrations;
        private readonly Dictionary<string, DeviceConnection> connections = new Dictionary<string, DeviceConnection>(StringComparer.Ordinal);
        private readonly Dictionary<string, ListViewItem> rows = new Dictionary<string, ListViewItem>(StringComparer.Ordinal);
        private readonly ListView list = new ListView();
        private readonly Button refresh = new Button();
        private readonly Button retry = new Button();
        private readonly Label footer = new Label();
        private readonly NotifyIcon tray = new NotifyIcon();
        private readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
        private bool updating;
        private bool exiting;
        private bool refreshing;

        public MainForm(SettingsStore store) {
            this.store = store;
            registrations = store.Load().ToDictionary(item => item.Id, StringComparer.Ordinal);
            Text = "Liki 再接続サポート";
            Font = new Font("Yu Gothic UI", 10);
            ClientSize = new Size(650, 340);
            MinimumSize = new Size(540, 300);
            StartPosition = FormStartPosition.CenterScreen;

            var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(14), ColumnCount = 1, RowCount = 4 };
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.Controls.Add(new Label { Text = "自動で接続する端末にチェックを付けてください。", AutoSize = true, Margin = new Padding(0, 0, 0, 10) }, 0, 0);
            list.Dock = DockStyle.Fill;
            list.View = View.Details;
            list.CheckBoxes = true;
            list.FullRowSelect = true;
            list.HideSelection = false;
            list.ShowItemToolTips = true;
            list.Columns.Add("ペアリング済み端末", 290);
            list.Columns.Add("Bluetooth LEの状態", 290);
            list.ItemCheck += RegistrationChanged;
            layout.Controls.Add(list, 0, 1);

            var buttons = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, Margin = new Padding(0, 10, 0, 8) };
            refresh.Text = "一覧を更新"; refresh.AutoSize = true;
            refresh.Click += async (sender, args) => await RefreshDevicesAsync();
            retry.Text = "選択端末を再確認"; retry.AutoSize = true;
            retry.Click += async (sender, args) => {
                foreach (ListViewItem row in list.SelectedItems) {
                    DeviceConnection connection;
                    if (connections.TryGetValue((string)row.Tag, out connection)) {
                        connection.RetryNow();
                        await connection.TickAsync();
                    }
                }
            };
            buttons.Controls.Add(refresh); buttons.Controls.Add(retry);
            layout.Controls.Add(buttons, 0, 2);
            footer.Text = "×で通知領域へ移動します。終了は通知領域のメニューから。";
            footer.AutoSize = true; footer.Dock = DockStyle.Fill;
            layout.Controls.Add(footer, 0, 3);
            Controls.Add(layout);

            var menu = new ContextMenuStrip();
            menu.Items.Add("開く", null, (sender, args) => ShowWindow());
            menu.Items.Add("終了", null, (sender, args) => { exiting = true; Close(); });
            tray.Text = "Liki 再接続サポート";
            tray.Icon = SystemIcons.Application;
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += (sender, args) => ShowWindow();
            tray.Visible = true;
            timer.Interval = 1000;
            timer.Tick += async (sender, args) => await TickAsync();
            Shown += async (sender, args) => {
                foreach (var item in registrations.Values) AddConnection(item);
                await RefreshDevicesAsync();
                timer.Start();
                await TickAsync();
            };
            FormClosing += (sender, args) => {
                if (args.CloseReason == CloseReason.UserClosing && !exiting) { args.Cancel = true; Hide(); }
            };
        }

        public void ShowWindow() {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        private void AddConnection(RegisteredDevice registration) {
            var connection = new DeviceConnection(registration);
            connection.Changed += () => UpdateStatus(registration.Id);
            connections.Add(registration.Id, connection);
        }

        private void UpdateStatus(string id) {
            ListViewItem row;
            DeviceConnection connection;
            if (rows.TryGetValue(id, out row)) row.SubItems[1].Text = connections.TryGetValue(id, out connection) ? connection.Status : "支援しない";
        }

        private async Task RefreshDevicesAsync() {
            if (refreshing || exiting) return;
            refreshing = true; refresh.Enabled = false;
            try {
                var paired = await DeviceInformation.FindAllAsync(BluetoothLEDevice.GetDeviceSelectorFromPairingState(true)).AsTask();
                if (exiting || IsDisposed) return;
                updating = true;
                list.BeginUpdate();
                try {
                    list.Items.Clear(); rows.Clear();
                    var names = paired.ToDictionary(item => item.Id, item => item.Name, StringComparer.Ordinal);
                    foreach (var item in registrations.Values) if (!names.ContainsKey(item.Id)) names.Add(item.Id, item.Name);
                    foreach (var item in names.OrderBy(item => item.Value, StringComparer.CurrentCultureIgnoreCase)) {
                        var row = new ListViewItem(String.IsNullOrWhiteSpace(item.Value) ? "名前のない端末" : item.Value) {
                            Tag = item.Key, Checked = registrations.ContainsKey(item.Key), ToolTipText = item.Key
                        };
                        row.SubItems.Add("支援しない");
                        list.Items.Add(row); rows.Add(item.Key, row); UpdateStatus(item.Key);
                    }
                } finally { list.EndUpdate(); updating = false; }
                footer.Text = "×で通知領域へ移動します。終了は通知領域のメニューから。";
            } catch (Exception error) {
                if (!exiting) footer.Text = "一覧を取得できません（0x" + error.HResult.ToString("X8") + "）。Bluetoothを確認してください。";
            } finally { refreshing = false; if (!IsDisposed) refresh.Enabled = true; }
        }

        private void RegistrationChanged(object sender, ItemCheckEventArgs args) {
            if (updating || exiting) return;
            var row = list.Items[args.Index];
            string id = (string)row.Tag;
            var next = registrations.Values.Where(item => item.Id != id).ToList();
            var registration = new RegisteredDevice { Id = id, Name = row.Text };
            bool enabled = args.NewValue == CheckState.Checked;
            if (enabled) next.Add(registration);
            try { store.Save(next); }
            catch (Exception error) {
                args.NewValue = args.CurrentValue;
                MessageBox.Show(this, "登録を保存できませんでした。\n" + error.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            if (enabled) { registrations[id] = registration; AddConnection(registration); }
            else {
                registrations.Remove(id);
                DeviceConnection connection;
                if (connections.TryGetValue(id, out connection)) { connections.Remove(id); connection.Dispose(); }
            }
            UpdateStatus(id);
        }

        private async Task TickAsync() {
            if (exiting) return;
            await Task.WhenAll(connections.Values.ToArray().Select(item => item.TickAsync()));
        }

        protected override void Dispose(bool disposing) {
            if (disposing) {
                exiting = true;
                timer.Stop(); timer.Dispose();
                foreach (var connection in connections.Values) connection.Dispose();
                connections.Clear();
                tray.Visible = false;
                var menu = tray.ContextMenuStrip;
                tray.Dispose();
                if (menu != null) menu.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
