<?php

declare(strict_types=1);

function wsFrame(string $payload, int $opcode = 0x1): string
{
    $finAndOpcode = 0x80 | ($opcode & 0x0f);
    $length = strlen($payload);

    if ($length <= 125) {
        return chr($finAndOpcode) . chr($length) . $payload;
    }

    if ($length <= 65535) {
        return chr($finAndOpcode) . chr(126) . pack('n', $length) . $payload;
    }

    return chr($finAndOpcode) . chr(127) . pack('NN', 0, $length) . $payload;
}

$mode = $_GET['mode'] ?? '';

if ($mode === 'diag') {
    header('Content-Type: application/json; charset=utf-8');

    $data = [
        'php_version' => PHP_VERSION,
        'sapi' => php_sapi_name(),
        'server_software' => $_SERVER['SERVER_SOFTWARE'] ?? null,
        'https' => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
        'extensions' => [
            'sockets' => extension_loaded('sockets'),
            'openssl' => extension_loaded('openssl'),
            'pcntl' => extension_loaded('pcntl'),
            'posix' => extension_loaded('posix'),
        ],
        'limits' => [
            'max_execution_time' => ini_get('max_execution_time'),
            'memory_limit' => ini_get('memory_limit'),
        ],
        'forwarded_upgrade_header' => $_SERVER['HTTP_UPGRADE'] ?? null,
        'forwarded_connection_header' => $_SERVER['HTTP_CONNECTION'] ?? null,
        'sec_websocket_key_seen' => isset($_SERVER['HTTP_SEC_WEBSOCKET_KEY']),
    ];

    echo json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    exit;
}

if ($mode === 'ws') {
    $upgrade = strtolower($_SERVER['HTTP_UPGRADE'] ?? '');
    $connection = strtolower($_SERVER['HTTP_CONNECTION'] ?? '');
    $key = $_SERVER['HTTP_SEC_WEBSOCKET_KEY'] ?? '';

    if ($upgrade !== 'websocket' || strpos($connection, 'upgrade') === false || $key === '') {
        http_response_code(400);
        header('Content-Type: text/plain; charset=utf-8');
        echo "Missing WebSocket upgrade headers.\n";
        exit;
    }

    $accept = base64_encode(sha1($key . '258EAFA5-E914-47DA-95CA-C5AB0DC85B11', true));

    header('HTTP/1.1 101 Switching Protocols');
    header('Upgrade: websocket');
    header('Connection: Upgrade');
    header('Sec-WebSocket-Accept: ' . $accept);

    @ob_end_flush();
    @flush();

    echo wsFrame('handshake-ok');
    @flush();

    usleep(500000);
    echo wsFrame(pack('n', 1000), 0x8);
    @flush();
    exit;
}

$scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'wss' : 'ws';
$host = $_SERVER['HTTP_HOST'] ?? 'localhost';
$self = strtok($_SERVER['REQUEST_URI'] ?? '/ws_capability_test.php', '?');
$wsUrl = $scheme . '://' . $host . $self . '?mode=ws';
$diagUrl = $self . '?mode=diag';
?><!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WebSocket Capability Test</title>
  <style>
    body { font-family: Arial, sans-serif; max-width: 860px; margin: 24px auto; padding: 0 16px; }
    .card { border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin-bottom: 14px; }
    button { padding: 10px 14px; cursor: pointer; }
    code { background: #f5f5f5; padding: 2px 6px; border-radius: 4px; }
    pre { background: #111; color: #eaeaea; padding: 12px; border-radius: 8px; white-space: pre-wrap; }
  </style>
</head>
<body>
  <h1>WebSocket Capability Test</h1>

  <div class="card">
    <p><strong>Step 1:</strong> Open diagnostics endpoint:</p>
    <p><a href="<?= htmlspecialchars($diagUrl, ENT_QUOTES, 'UTF-8') ?>" target="_blank"><?= htmlspecialchars($diagUrl, ENT_QUOTES, 'UTF-8') ?></a></p>
    <p>Check that <code>sockets</code> is true.</p>
  </div>

  <div class="card">
    <p><strong>Step 2:</strong> Test WebSocket upgrade and short message:</p>
    <p>URL: <code id="ws-url"><?= htmlspecialchars($wsUrl, ENT_QUOTES, 'UTF-8') ?></code></p>
    <button id="btn">Run WebSocket Test</button>
    <pre id="log">Ready.</pre>
  </div>

  <script>
    const log = document.getElementById('log');
    const wsUrl = document.getElementById('ws-url').textContent;

    function append(line) {
      log.textContent += "\n" + line;
    }

    document.getElementById('btn').addEventListener('click', () => {
      log.textContent = 'Connecting...';
      let opened = false;

      try {
        const ws = new WebSocket(wsUrl);

        ws.onopen = () => {
          opened = true;
          append('OPEN: upgrade succeeded');
        };

        ws.onmessage = (event) => {
          append('MESSAGE: ' + event.data);
        };

        ws.onerror = () => {
          append('ERROR: connection failed or blocked by host/proxy');
        };

        ws.onclose = (event) => {
          append(`CLOSE: code=${event.code} reason=${event.reason || '(none)'}`);
          if (!opened) {
            append('RESULT: upgrade likely not supported on this hosting path.');
          } else {
            append('RESULT: basic WebSocket handshake worked. Long-running support may still be limited on shared hosting.');
          }
        };
      } catch (e) {
        append('EXCEPTION: ' + (e && e.message ? e.message : e));
      }
    });
  </script>
</body>
</html>
