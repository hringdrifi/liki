using System;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;

namespace LikiReconnect {
    internal sealed class DeviceConnection : IDisposable {
        private static readonly Guid HidService = new Guid("00001812-0000-1000-8000-00805f9b34fb");
        private readonly SynchronizationContext context;
        private readonly CancellationTokenSource cancellation = new CancellationTokenSource();
        private readonly RetrySchedule retry = new RetrySchedule();
        private BluetoothLEDevice device;
        private GattSession session;
        private bool busy;
        private bool disposed;
        private int changes;
        private int refreshedChanges;
        private bool forceRefresh;
        private DateTimeOffset refreshAfter;
        private bool? hasHid;

        public RegisteredDevice Registration { get; private set; }
        public string Status { get; private set; }
        public event Action Changed;

        public DeviceConnection(RegisteredDevice registration) {
            Registration = registration;
            Status = "接続待ち";
            context = SynchronizationContext.Current ?? new SynchronizationContext();
        }

        public void RetryNow() {
            retry.Reset();
            forceRefresh = true;
        }

        // Called on the UI thread. Each device has at most one outstanding GATT operation.
        public async Task TickAsync() {
            if (disposed || busy || !retry.IsDue(DateTimeOffset.UtcNow)) return;
            busy = true;
            try {
                if (device == null) {
                    SetStatus("接続準備中…");
                    device = await BluetoothLEDevice.FromIdAsync(Registration.Id).AsTask(cancellation.Token);
                    if (disposed) { ReleaseHandles(); return; }
                    if (device == null) { Failed("端末を利用できません"); return; }
                    device.ConnectionStatusChanged += ConnectionChanged;
                    device.GattServicesChanged += ServicesChanged;
                }
                if (!device.DeviceInformation.Pairing.IsPaired) {
                    Failed("ペアリングが解除されています");
                    ReleaseHandles();
                    return;
                }
                if (session == null) {
                    session = await GattSession.FromDeviceIdAsync(device.BluetoothDeviceId).AsTask(cancellation.Token);
                    if (disposed) { ReleaseHandles(); return; }
                    if (session == null || !session.CanMaintainConnection) {
                        Failed("接続維持を利用できません");
                        ReleaseHandles();
                        return;
                    }
                    session.MaintainConnection = true;
                }

                bool connected = device.ConnectionStatus == BluetoothConnectionStatus.Connected;
                bool refresh = forceRefresh || (changes != refreshedChanges && DateTimeOffset.UtcNow >= refreshAfter);
                if (connected && !refresh) { SetConnectedStatus(); return; }

                SetStatus(connected ? "接続済み・サービス確認中…" : "接続要求中…");
                int version = changes;
                forceRefresh = false;
                var result = await device.GetGattServicesAsync(BluetoothCacheMode.Uncached).AsTask(cancellation.Token);
                try {
                    if (disposed) return;
                    if (result.Status == GattCommunicationStatus.Success) {
                        hasHid = false;
                        foreach (var service in result.Services) if (service.Uuid == HidService) hasHid = true;
                        refreshedChanges = version;
                        retry.Reset();
                        SetConnectedStatus();
                    } else {
                        Failed(result.Status == GattCommunicationStatus.Unreachable
                            ? "未接続・再試行待ち" : "サービス確認失敗：" + result.Status);
                    }
                } finally {
                    foreach (var service in result.Services) service.Dispose();
                }
            } catch (OperationCanceledException) {
                // Unregistering and exiting cancel the app's pending operation.
            } catch (Exception error) {
                if (!disposed) {
                    Failed("接続エラー（0x" + error.HResult.ToString("X8") + "）");
                    ReleaseHandles();
                }
            } finally {
                busy = false;
                if (disposed) ReleaseHandles();
            }
        }

        private void ConnectionChanged(BluetoothLEDevice sender, object unused) {
            Post(() => {
                if (sender != device) return;
                retry.Reset();
                if (sender.ConnectionStatus == BluetoothConnectionStatus.Connected) SetConnectedStatus();
                else { hasHid = null; SetStatus("未接続・再接続待ち"); }
            });
        }

        private void ServicesChanged(BluetoothLEDevice sender, object unused) {
            Post(() => {
                if (sender != device) return;
                changes++;
                refreshAfter = DateTimeOffset.UtcNow.AddMilliseconds(750);
                retry.Reset();
            });
        }

        private void Post(Action action) {
            context.Post(unused => { if (!disposed) action(); }, null);
        }

        private void Failed(string status) {
            retry.Failed(DateTimeOffset.UtcNow);
            SetStatus(status);
        }

        private void SetConnectedStatus() {
            if (device == null || device.ConnectionStatus != BluetoothConnectionStatus.Connected) {
                SetStatus("未接続・再接続待ち");
                return;
            }
            SetStatus(hasHid == false ? "接続済み（Likiの公開待ち）" : "接続済み");
        }

        private void SetStatus(string status) {
            if (disposed || Status == status) return;
            Status = status;
            if (Changed != null) Changed();
        }

        private void ReleaseHandles() {
            if (session != null) {
                try { session.MaintainConnection = false; } catch (Exception) { }
                session.Dispose();
                session = null;
            }
            if (device != null) {
                device.ConnectionStatusChanged -= ConnectionChanged;
                device.GattServicesChanged -= ServicesChanged;
                device.Dispose();
                device = null;
            }
            hasHid = null;
            refreshedChanges = changes;
        }

        public void Dispose() {
            if (disposed) return;
            disposed = true;
            cancellation.Cancel();
            // MaintainConnection must be released even if a discovery is still finishing.
            ReleaseHandles();
        }
    }
}
