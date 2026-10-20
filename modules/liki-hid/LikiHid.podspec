Pod::Spec.new do |s|
  s.name = 'LikiHid'
  s.version = '1.0.2'
  s.summary = 'Liki Bluetooth HID over GATT module'
  s.description = s.summary
  s.homepage = 'https://github.com/hringdrifi/liki'
  s.license = { :type => 'AGPL-3.0', :file => 'ios/LICENSE.darwin-bt-remote.txt' }
  s.author = 'hringdrifi'
  s.source = { :git => 'https://github.com/hringdrifi/liki.git' }
  s.platforms = { :ios => '16.4' }
  s.swift_version = '5.9'
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  s.source_files = 'ios/*.swift'
  s.resource_bundles = { 'LikiHid' => ['android/src/main/assets/*.json'] }
  s.frameworks = 'CoreBluetooth', 'Combine', 'UIKit'
end
