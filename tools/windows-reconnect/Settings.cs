using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;

namespace LikiReconnect {
    [DataContract]
    public sealed class RegisteredDevice {
        [DataMember] public string Id { get; set; }
        [DataMember] public string Name { get; set; }
    }

    internal sealed class SettingsStore {
        private readonly string path;
        public SettingsStore(string path) { this.path = path; }

        public List<RegisteredDevice> Load() {
            if (!File.Exists(path)) return new List<RegisteredDevice>();
            using (var stream = File.OpenRead(path)) {
                var items = (List<RegisteredDevice>)Serializer().ReadObject(stream);
                if (items == null || items.Any(item => item == null || String.IsNullOrWhiteSpace(item.Id)))
                    throw new SerializationException("登録ファイルの内容が不正です。");
                return items.GroupBy(item => item.Id, StringComparer.Ordinal).Select(group => group.First()).ToList();
            }
        }

        public void Save(IEnumerable<RegisteredDevice> devices) {
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            string temporary = path + ".tmp";
            try {
                using (var stream = File.Create(temporary)) Serializer().WriteObject(stream, devices.ToList());
                if (File.Exists(path)) File.Replace(temporary, path, null);
                else File.Move(temporary, path);
            } finally {
                if (File.Exists(temporary)) File.Delete(temporary);
            }
        }

        private static DataContractJsonSerializer Serializer() {
            return new DataContractJsonSerializer(typeof(List<RegisteredDevice>));
        }
    }

    internal sealed class RetrySchedule {
        private int failures;
        public DateTimeOffset NextAttempt { get; private set; }
        public RetrySchedule() { Reset(); }
        public bool IsDue(DateTimeOffset now) { return now >= NextAttempt; }
        public void Reset() { failures = 0; NextAttempt = DateTimeOffset.MinValue; }
        public void Failed(DateTimeOffset now) {
            int seconds = Math.Min(30, 2 * (1 << Math.Min(failures, 4)));
            failures++;
            NextAttempt = now.AddSeconds(seconds);
        }
    }
}
