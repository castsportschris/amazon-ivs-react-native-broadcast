require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "amazon-ivs-react-native-broadcast"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "11.0" }
  s.source       = { :git => "https://github.com/apiko-dev/amazon-ivs-react-native-broadcast.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift}"

  # CoreML models for the auto-tracking feature. .mlpackage and .mlmodel
  # files placed in ios/MLModels/ are bundled with the host app and compiled
  # by Xcode's build phase to .mlmodelc at build time.
  s.resources = ["ios/MLModels/*.mlpackage", "ios/MLModels/*.mlmodel"]

  s.dependency "React-Core"
  s.dependency "AmazonIVSBroadcast", "~> #{package["sdkVersion"]["ios"]}"
end
