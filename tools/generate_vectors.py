"""Cross-language fixtures. All passwords and login keys here are synthetic."""
import base64
import json
from pathlib import Path
from urllib.parse import quote
from Crypto.Cipher import AES, DES
from Crypto.Util.Padding import pad

root = Path(__file__).resolve().parents[1]
password = 'Example_password_123!'
vectors = []
for key in (b'01234567', b'0123456789abcdef', b'0123456789abcdefghijklmn', b'0123456789abcdefghijklmnopqrstuv'):
    algorithm = DES if len(key) == 8 else AES
    encrypted = algorithm.new(key, algorithm.MODE_ECB).encrypt(pad(password.encode(), algorithm.block_size))
    vectors.append({'key': base64.b64encode(key).decode(), 'password': password, 'encrypted': base64.b64encode(encrypted).decode()})
payload = {'campus': 'demo-campus', 'lon': 119.19, 'lat': 26.05, 'startTime': '21:00', 'endTime': '23:00',
           'actualLocation': '示例校区 A&B <test>', 'caIsNo': '0', 'collUnit': '示例学院', 'way': 1, 'isLate': 0}
key = b'apexinfoapexinfo'
plain = json.dumps(payload, ensure_ascii=False, separators=(',', ':'))
encrypted = base64.b64encode(AES.new(key, AES.MODE_CBC, key).encrypt(pad(plain.encode(), AES.block_size))).decode()
fixture = {'passwordVectors': vectors, 'payload': payload, 'encrypted': encrypted, 'encoded': quote(encrypted, safe='')}
out = root/'android-app/app/src/test/resources/crypto-vectors.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(fixture, ensure_ascii=False, indent=2), encoding='utf-8')
print('Created synthetic Python crypto fixtures.')
