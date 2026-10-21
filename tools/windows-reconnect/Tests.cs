using System;
using System.IO;
using System.Linq;
using System.Runtime.Serialization;

namespace LikiReconnect {
    internal static class Tests {
        private static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
        public static void Main() {
            string directory = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "test-" + Guid.NewGuid().ToString("N"));
            string path = Path.Combine(directory, "devices.json");
            try {
                var store = new SettingsStore(path);
                Check(store.Load().Count == 0, "First run must have no registered devices.");
                store.Save(new[] {
                    new RegisteredDevice { Id = "device-a", Name = "同じ名前" },
                    new RegisteredDevice { Id = "device-b", Name = "同じ名前" }
                });
                var loaded = new SettingsStore(path).Load();
                Check(loaded.Count == 2 && loaded.Select(item => item.Id).Distinct().Count() == 2,
                    "Registration must use device IDs, not names.");
                store.Save(loaded.Where(item => item.Id != "device-a"));
                Check(store.Load().Single().Id == "device-b", "Unregister must persist without removing another device.");
                File.WriteAllText(path, "broken");
                bool rejected = false;
                try { store.Load(); } catch (SerializationException) { rejected = true; }
                Check(rejected && File.ReadAllText(path) == "broken", "Corrupt settings must not be silently overwritten.");

                var schedule = new RetrySchedule();
                var now = DateTimeOffset.UtcNow;
                Check(schedule.IsDue(now), "First connection must not be delayed.");
                int[] delays = { 2, 4, 8, 16, 30, 30, 30 };
                foreach (int delay in delays) {
                    schedule.Failed(now);
                    Check(!schedule.IsDue(now.AddSeconds(delay - 1)), "Retry ran before its backoff expired.");
                    Check(schedule.IsDue(now.AddSeconds(delay)), "Retry did not run when due.");
                    now = now.AddSeconds(delay);
                }
                schedule.Reset();
                Check(schedule.IsDue(now), "Connection event/manual retry must clear backoff.");
                schedule.Failed(now);
                Check(schedule.NextAttempt == now.AddSeconds(2), "Reset must restore the initial backoff.");
                Console.WriteLine("PASS: registration persistence, duplicate names, unregister, corrupt settings, retry backoff/reset");
            } finally {
                if (File.Exists(path)) File.Delete(path);
                if (Directory.Exists(directory)) Directory.Delete(directory);
            }
        }
    }
}
