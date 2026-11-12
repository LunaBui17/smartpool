"""Exercise the embedded dashboard in headless Chrome with a local API fixture.

Uses Python's standard library. Saves desktop/mobile previews under build/ui-check.
These are browser tests, not substitutes for testing ESP32 radio/relay hardware.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import copy

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/ui-check"
OUTPUT.mkdir(parents=True, exist_ok=True)
CHROME = Path("C:/Program Files/Google/Chrome/Application/chrome.exe")

INITIAL = {
    "wifi_connected": False, "station_enabled": False, "station_supported": True,
    "ap_enabled": True, "ap_ssid": "SmartPool-Setup", "mqtt_supported": True,
    "mqtt_connected": False, "http_supported": True, "relay1_supported": True,
    "relay2_supported": True, "relay1": False, "relay2": False,
    "temperature_supported": True, "water_supported": True,
    "temperature_enabled": True, "water_enabled": True, "temperature": 27.4,
    "water_raw": 2680, "water_normal": True, "sample_age_seconds": 1,
    "ssid": "Home Pool", "ip": "",
    "mqtt_state": "waiting_wifi", "http_state": "waiting_wifi", "http_status_code": 0,
    "http_error": "", "http_last_attempt_age_seconds": -1,
    "http_last_success_age_seconds": -1, "http_queued_samples": 12,
    "clock_ready": False,
    "automation": [
        {"channel": 1, "enabled": False, "sensor": "water", "comparison": "below",
         "start_threshold": 1500, "stop_mode": "threshold", "stop_threshold": 2500,
         "duration_seconds": 300, "paused": False, "running": False, "reason": "disabled",
         "remaining_seconds": 0, "last_stop_reason": "disabled"},
        {"channel": 2, "enabled": False, "sensor": "water", "comparison": "above",
         "start_threshold": 3000, "stop_mode": "threshold", "stop_threshold": 2000,
         "duration_seconds": 300, "paused": False, "running": False, "reason": "disabled",
         "remaining_seconds": 0, "last_stop_reason": "disabled"},
    ],
}
device = copy.deepcopy(INITIAL)
calls = []
TEST_SCRIPT = r"""
<script>
(async()=>{
 const checks=[];
 const check=(ok,name)=>{if(!ok)throw new Error(name);checks.push(name);};
 const wait=async predicate=>{for(let i=0;i<200;i++){if(predicate())return;await new Promise(r=>setTimeout(r,25));}throw new Error('Timed out');};
 const click=async selector=>{document.querySelector(selector).click();await wait(()=>!busy);};
 try{
  await wait(()=>state!==null);
  check($('temperature').textContent==='27.4','temperature reading');
  check($('water').textContent.includes('bình thường'),'water reading');
  check($('mqtt').textContent==='Chờ Wi-Fi'&&$('http').textContent==='Chờ Wi-Fi','offline service states');
  const fixture=async update=>{await fetch('/fixture',{method:'POST',body:JSON.stringify(update)});await refresh();};
  for(const [phase,label,tone] of [['disabled','Đã tắt',''],['missing_credentials','Chưa có tài khoản','error'],['waiting_wifi','Chờ Wi-Fi','pending'],['waiting_time','Chờ đồng bộ giờ','pending'],['connecting','Đang kết nối','pending'],['connected','Đã kết nối','good'],['disconnected','Mất kết nối','error'],['error','Lỗi kết nối','error']]){
    await fixture({mqtt_state:phase,mqtt_connected:phase==='connected'});
    check($('mqtt').textContent===label&&(!tone||$('mqtt').classList.contains(tone)),'MQTT '+phase);
  }
  await fixture({mqtt_state:'waiting_time',mqtt_connected:false,clock_ready:false});
  check(!$('sync-time').hidden&&!$('sync-time').disabled,'browser clock sync available while NTP waits');
  await click('#sync-time');
  check(state.clock_ready&&$('sync-time').hidden&&$('mqtt').textContent==='Đang kết nối','browser clock sync unblocks MQTT');
  for(const [phase,label,tone] of [['disabled','Đã tắt',''],['waiting_wifi','Chờ Wi-Fi','pending'],['pending','Chưa gửi','pending'],['sending','Đang gửi','pending'],['success','Gửi thành công','good'],['error','Gửi thất bại','error']]){
    await fixture({http_state:phase,http_status_code:phase==='error'?404:200,http_last_attempt_age_seconds:3,http_last_success_age_seconds:90,http_queued_samples:128});
    check($('http').textContent===label&&(!tone||$('http').classList.contains(tone)),'HTTP '+phase);
  }
  check($('http-detail').textContent.includes('404'),'HTTP error status code');
  check($('http-last-success').textContent.includes('90')&&$('http-queue').textContent.includes('128'),'HTTP success age and backlog');
  await fixture({http_state:'sending',http_status_code:404});
  check($('http-detail').textContent.includes('404')&&$('http').textContent==='Đang gửi','last HTTP failure remains visible during retry');
  await fixture({http_state:'error',http_status_code:0,http_error:'ESP_ERR_TIMEOUT'});
  check($('http-detail').textContent.includes('ESP_ERR_TIMEOUT'),'HTTP transport error');
  await fixture({http_state:'success',http_status_code:200,http_error:'',http_queued_samples:0,http_last_success_age_seconds:1});
  check($('http').classList.contains('good')&&$('http-detail').textContent.includes('200'),'HTTP recovery');
  await fixture({mqtt_supported:false,http_supported:false});
  check($('mqtt').textContent==='Đã tắt'&&$('http').textContent==='Đã tắt'&&$('http-queue').textContent==='','disabled transports');
  await fixture({mqtt_supported:true,http_supported:true,mqtt_state:'connected',mqtt_connected:true});
  check(autoField(1,'start_threshold').value==='1500'&&!autoField(1,'enabled').checked,'automation defaults disabled');
  autoField(1,'enabled').checked=true;autoField(1,'sensor').value='temperature';autoField(1,'comparison').value='above';autoField(1,'start_threshold').value='30.1';autoField(1,'stop_threshold').value='28.2';autoField(1,'duration_seconds').value='10';
  $('auto1').dispatchEvent(new Event('input',{bubbles:true}));await refresh();
  check(autoField(1,'sensor').value==='temperature'&&autoField(1,'start_threshold').value==='30.1','polling preserves unsaved pump setup');
  $('auto1').dispatchEvent(new Event('submit',{cancelable:true}));await wait(()=>!busy);
  check(state.automation[0].enabled&&state.automation[0].sensor==='temperature'&&state.automation[0].stop_threshold===28.2,'temperature threshold rule saved');
  autoField(1,'stop_threshold').value='32';$('auto1').dispatchEvent(new Event('submit',{cancelable:true}));await wait(()=>!busy);
  check($('notice').classList.contains('error')&&state.automation[0].stop_threshold===28.2,'invalid hysteresis rejected before apply');
  autoField(1,'stop_threshold').value='28.2';
  autoField(2,'enabled').checked=true;autoField(2,'stop_mode').value='duration';autoField(2,'duration_seconds').value='7';$('auto2').dispatchEvent(new Event('change',{bubbles:true}));
  check(autoField(2,'stop_threshold').disabled,'timed rule does not require stop threshold');
  $('auto2').dispatchEvent(new Event('submit',{cancelable:true}));await wait(()=>!busy);
  check(state.automation[1].enabled&&state.automation[1].stop_mode==='duration'&&state.automation[1].duration_seconds===7,'independent water timed rule saved');
  await click('[data-control="relay1"]');check(state.relay1,'local relay ON without router/MQTT');
  check(state.automation[0].paused&&!state.automation[1].paused,'manual override pauses only its own rule');
  await click('[data-control="relay2"]');check(state.relay1&&state.relay2,'both pumps ON simultaneously');
  await click('[data-control="relay1"]');check(!state.relay1&&state.relay2,'pump in OFF leaves pump out ON');
  await click('[data-control="relay1"]');check(state.relay1&&state.relay2,'pump in restarts independently');
  await click('[data-control="relay2"]');check(state.relay1&&!state.relay2,'pump out OFF leaves pump in ON');
  await click('[data-control="relay2"]');check(state.relay1&&state.relay2,'pump out restarts independently');
  await fetch('/fixture',{method:'POST',body:JSON.stringify({reject_next_control:true})});
  await click('[data-control="relay1"]');check($('notice').classList.contains('error'),'command failure visible');
  await click('#stop');check(!state.relay1&&!state.relay2,'stop both pumps');
  check(state.automation.every(rule=>rule.paused),'stop all pauses both automatic rules');
  $('auto1').dispatchEvent(new Event('submit',{cancelable:true}));await wait(()=>!busy);
  check(!state.automation[0].paused&&state.automation[1].paused,'save and apply rearms one pump only');
  await click('[data-control="temperature"]');check(!state.temperature_enabled&&$('temperature').textContent==='—','sensor OFF clears reading');
  await click('#station');check(state.station_enabled,'enable router connection');
  await click('#station');check(!state.station_enabled,'return to local AP');
  await click('#scan');check($('networks').options.length===3,'Wi-Fi scanning');
  check(!window.injected,'SSID is rendered as text');
  $('networks').selectedIndex=1;$('networks').dispatchEvent(new Event('change'));
  check($('ssid').value==='<img src=x onerror=window.injected=true>','SSID selection');
  $('ssid').value='Pool Wi-Fi';$('password').value='test-password';
  $('wifi-form').dispatchEvent(new Event('submit',{cancelable:true}));await wait(()=>!busy);
  check($('password').value==='','password cleared after save');
  await fetch('/fixture',{method:'POST',body:JSON.stringify({relay2_supported:false,temperature_supported:false,temperature_enabled:false})});await refresh();
  check(document.querySelector('[data-control="relay2"]').disabled,'disabled relay locked');
  check(document.querySelector('[data-control="temperature"]').disabled,'disabled sensor locked');
  await fetch('/fixture',{method:'POST',body:JSON.stringify({fail:true})});await refresh();
  check(document.querySelector('[data-control="relay1"]').disabled,'controls disabled on lost connection');
  check($('mqtt').textContent==='Không rõ'&&$('http').textContent==='Không rõ'&&!$('mqtt').classList.contains('good')&&!$('http').classList.contains('good'),'service badges clear on device connection loss');
  await fetch('/fixture',{method:'POST',body:JSON.stringify({fail:false})});await refresh();
  check(!document.querySelector('[data-control="relay1"]').disabled,'controls recover after reconnect');
  check(document.body.scrollWidth<=document.body.clientWidth,'no horizontal overflow');
  const result=document.createElement('pre');result.id='test-results';result.textContent='PASS '+checks.length+' checks: '+checks.join(', ');document.body.append(result);
 }catch(error){const result=document.createElement('pre');result.id='test-results';result.textContent='FAIL '+error.stack;document.body.append(result);}
})();
</script>
"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, value, status=200, content_type="application/json"):
        data = value.encode() if isinstance(value, str) else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/api/status"):
            self.reply({"ok": False} if device.get("fail") else device,
                       503 if device.get("fail") else 200)
        elif self.path == "/api/networks":
            self.reply([{"ssid": "<img src=x onerror=window.injected=true>", "rssi": -50, "secure": True},
                        {"ssid": "Home Pool", "rssi": -68, "secure": False}])
        elif self.path.startswith("/?") or self.path == "/":
            page = (ROOT / "main/web/index.html").read_text(encoding="utf-8")
            if "mobile" in self.path:
                # Windows headless Chrome has a minimum window width of 500px.
                # Constrain the document to the screenshot width as well.
                page = page.replace("</head>", "<style>html,body{width:390px;max-width:390px}</style></head>")
            if "test" in self.path:
                page = page.replace("</body>", TEST_SCRIPT + "</body>")
            self.reply(page, content_type="text/html; charset=utf-8")
        else:
            self.reply({}, 404)

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        calls.append((self.path, data))
        if self.path == "/api/control":
            if device.pop("reject_next_control", False):
                return self.reply({"ok": False, "message": "Control request failed"}, 409)
            key, enabled = data["target"], data["enabled"]
            if key == "stop":
                device.update(relay1=False, relay2=False)
                for rule in device["automation"]:
                    rule.update(paused=True, running=False, reason="manual", last_stop_reason="manual")
            elif key in ("relay1", "relay2"):
                device[key] = enabled
                rule = device["automation"][0 if key == "relay1" else 1]
                rule.update(paused=True, running=False, reason="manual", last_stop_reason="manual")
            else:
                device[key + "_enabled"] = enabled
                device["temperature"] = None
        elif self.path == "/api/automation":
            index = data["channel"] - 1
            rule = device["automation"][index]
            stop = data["enabled"] or rule["running"]
            rule.update(data)
            rule.update(paused=False, running=False, reason="waiting_sensor" if data["enabled"] else "disabled", remaining_seconds=0, last_stop_reason="disabled")
            if stop:
                device["relay1" if index == 0 else "relay2"] = False
        elif self.path == "/api/time":
            timestamp = data.get("unix_time")
            if type(timestamp) is not int or not 1704067200 <= timestamp <= 4102444799:
                return self.reply({"ok": False, "message": "Invalid time"}, 400)
            device.update(clock_ready=True, mqtt_state="connecting")
        elif self.path == "/api/network":
            device["station_enabled"] = data["enabled"]
            device.update(relay1=False, relay2=False)
        elif self.path == "/fixture":
            device.update(data)
        elif self.path == "/wifi":
            device["ssid"] = data["ssid"]
        self.reply({"ok": True})


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    for name, width, height in (("desktop", 1440, 2100), ("mobile", 390, 3600)):
        device.clear()
        device.update(copy.deepcopy(INITIAL))
        command = [str(CHROME), "--headless=new", "--disable-gpu", "--no-first-run",
                   "--disable-background-networking", "--no-default-browser-check",
                   f"--user-data-dir={OUTPUT / ('profile-' + name)}",
                   f"--window-size={width},{height}", "--hide-scrollbars",
                   "--virtual-time-budget=15000", "--dump-dom",
                   f"http://127.0.0.1:{server.server_port}/?test-{name}"]
        result = subprocess.run(command, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=50)
        (OUTPUT / (name + "-test.html")).write_text(result.stdout, encoding="utf-8")
        assert 'id="test-results">PASS ' in result.stdout, result.stderr[-1500:] + result.stdout[-2500:]
        print(f"PASS: {name} dashboard controls, errors, scanning, disabled features, reconnect, and layout", flush=True)
        device.clear()
        device.update(copy.deepcopy(INITIAL))
        device.update(wifi_connected=True, station_enabled=True, ip="192.168.60.103",
                      mqtt_state="connected", mqtt_connected=True, http_state="error",
                      http_status_code=404, http_last_attempt_age_seconds=4,
                      http_last_success_age_seconds=120, http_queued_samples=128)
        command = [arg for arg in command if arg != "--dump-dom"]
        command[-1] = f"http://127.0.0.1:{server.server_port}/?preview-{name}"
        command.insert(-1, f"--screenshot={OUTPUT / (name + '.png')}")
        subprocess.run(command, capture_output=True, timeout=50, check=True)
    assert any(path == "/wifi" and body["ssid"] == "Pool Wi-Fi" for path, body in calls)
    assert any(path == "/api/time" and 1704067200 <= body["unix_time"] <= 4102444799 for path, body in calls)
    assert any(path == "/api/automation" and body["sensor"] == "temperature" for path, body in calls)
finally:
    server.shutdown()
