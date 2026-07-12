import json
import urllib.request
import urllib.error

with open('/data/data/com.termux/files/home/antigravity-proxy-fork/antigravity-proxy-rust/antigravity-accounts.json') as f:
    accounts = json.load(f)

account = accounts['accounts'][0]
access_token = account['accessToken']
project_id = account['projectId']
fp = account['fingerprint']

url = "https://daily-cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels"
req = urllib.request.Request(url, method='POST')
req.add_header('Authorization', f'Bearer {access_token}')
req.add_header('Content-Type', 'application/json')
req.add_header('User-Agent', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/2.2.1 Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36')
req.add_header('x-goog-api-client', fp['apiClient'])
req.add_header('x-goog-quotauser', fp['quotaUser'])
req.add_header('x-client-device-id', fp['deviceId'])
if 'clientMetadata' in fp and fp['clientMetadata']:
    req.add_header('client-metadata', json.dumps(fp['clientMetadata']))

data = json.dumps({"project": project_id}).encode('utf-8')

try:
    with urllib.request.urlopen(req, data=data) as response:
        print("fetchAvailableModels OK")
except urllib.error.HTTPError as e:
    print("fetchAvailableModels Error:", e.code, e.reason, e.read().decode('utf-8'))

url_gen = "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse"
req_gen = urllib.request.Request(url_gen, method='POST')
req_gen.add_header('Authorization', f'Bearer {access_token}')
req_gen.add_header('Content-Type', 'application/json')
req_gen.add_header('User-Agent', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/2.2.1 Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36')
req_gen.add_header('x-goog-api-client', fp['apiClient'])
req_gen.add_header('x-goog-quotauser', fp['quotaUser'])
req_gen.add_header('x-client-device-id', fp['deviceId'])
if 'clientMetadata' in fp and fp['clientMetadata']:
    req_gen.add_header('client-metadata', json.dumps(fp['clientMetadata']))

data_gen = json.dumps({
    "project": project_id,
    "model": "gemini-3.5-flash-low",
    "userAgent": "antigravity",
    "requestId": "agent-123",
    "requestType": "agent",
    "request": {
        "contents": [{"role": "user", "parts": [{"text": "hello"}]}],
        "sessionId": "12345"
    }
}).encode('utf-8')

try:
    with urllib.request.urlopen(req_gen, data=data_gen) as response:
        print("streamGenerateContent OK:", response.readline().decode('utf-8'))
except urllib.error.HTTPError as e:
    print("streamGenerateContent Error:", e.code, e.reason, e.read().decode('utf-8'))
