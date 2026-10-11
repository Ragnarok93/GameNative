#!/usr/bin/env python3
"""Execute the production sidecar reader with independently unavailable domains."""
import pathlib, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
s=(root/'app/src/main/cpp/lsfg-vk-android/src/context.cpp').read_text()
reader=s[s.index('struct RuntimePressureSample'):s.index('VkImageSubresourceRange colorSubresourceRange()')]
source=r'''
#include <cassert>
#include <filesystem>
#include <fstream>
#include <chrono>
#include <cmath>
#include <string>
#include <iostream>
'''+reader+r'''
int main(int argc,char**argv) {
 assert(argc==2); const std::string test=argv[1];
 auto root=std::filesystem::temp_directory_path()/"lsfg-pressure-audit";
 std::filesystem::create_directories(root);
 auto now=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
 auto write=[&](double gpu,double source,double p95,double slow,int flag,long long age=0){std::ofstream out(root/"runtime-pressure.txt");out<<"timestamp_ms="<<now-age<<"\ngpu_usage_percent="<<gpu<<"\nthermal_status=4\nsource_fps="<<source<<"\nframe_time_p95_ms="<<p95<<"\nslow_frame_ratio="<<slow<<"\nsource_metrics_valid="<<flag<<"\n";};
 if(test=="gpu_without_source") {write(98,-1,-1,-1,0);assert(readRuntimePressure(root/"conf.toml").valid);}
 else if(test=="thermal_without_gpu") {write(-1,30,33,.1,1);assert(readRuntimePressure(root/"conf.toml").thermalStatusValid);}
 else if(test=="stale_timestamp") {write(98,30,33,.1,1,3000);assert(!readRuntimePressure(root/"conf.toml").valid);}
 else if(test=="future_timestamp") {write(98,30,33,.1,1,-3000);assert(!readRuntimePressure(root/"conf.toml").valid);}
 else if(test=="measured_zero") {write(0,0,0,0,1);auto s=readRuntimePressure(root/"conf.toml");assert(s.valid&&s.sourceFpsValid&&s.frameTimeP95Valid&&s.slowFrameRatioValid);}
 else if(test=="independent_source") {write(-1,30,-1,-1,1);auto s=readRuntimePressure(root/"conf.toml");assert(!s.valid&&s.sourceFpsValid&&!s.frameTimeP95Valid&&!s.slowFrameRatioValid);}
 else return 2;
 std::filesystem::remove_all(root);std::cout<<test<<" passed\n";
}
'''
with tempfile.TemporaryDirectory() as d:
 p=pathlib.Path(d);(p/'test.cpp').write_text(source)
 subprocess.run(['c++','-std=c++20','-Wall','-Wextra','-Werror',str(p/'test.cpp'),'-o',str(p/'test')],check=True)
 for case in ('gpu_without_source','thermal_without_gpu','stale_timestamp','future_timestamp','measured_zero','independent_source'):
  subprocess.run([str(p/'test'),case],check=True)
