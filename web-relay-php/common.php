<?php

declare(strict_types=1);

define('RELAY_STORAGE_ROOT', __DIR__ . DIRECTORY_SEPARATOR . 'storage');
define('RELAY_MAX_FILE_SIZE', 1024 * 1024 * 1024); // 1GB
define('RELAY_SESSION_TTL_SECONDS', 24 * 60 * 60);
define('RELAY_INIT_KEY', (string)(getenv('RELAY_INIT_KEY') ?: ''));

if (!is_dir(RELAY_STORAGE_ROOT)) {
    @mkdir(RELAY_STORAGE_ROOT, 0775, true);
}

function relay_json_response(int $statusCode, array $payload): void {
    http_response_code($statusCode);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($payload, JSON_UNESCAPED_SLASHES);
    exit;
}

function relay_get_transfer_id(): string {
    $id = $_GET['id'] ?? '';
    if (!preg_match('/^[A-Za-z0-9_-]{8,64}$/', $id)) {
        relay_json_response(400, ['error' => 'Invalid transfer id']);
    }
    return $id;
}

function relay_get_token(): string {
    $token = $_GET['token'] ?? '';
    if (!preg_match('/^[A-Za-z0-9_-]{16,128}$/', $token)) {
        relay_json_response(400, ['error' => 'Invalid token']);
    }
    return $token;
}

function relay_get_init_key(): string {
    $headerValue = (string)($_SERVER['HTTP_X_RETRODRIVE_INIT_KEY'] ?? '');
    if ($headerValue !== '') {
        return $headerValue;
    }
    return (string)($_GET['init_key'] ?? $_POST['init_key'] ?? '');
}

function relay_require_valid_init_key(): void {
    if (RELAY_INIT_KEY === '') {
        relay_json_response(500, ['error' => 'Server relay init key is not configured']);
    }

    $provided = relay_get_init_key();
    if ($provided === '' || !hash_equals(RELAY_INIT_KEY, $provided)) {
        relay_json_response(403, ['error' => 'Invalid relay init key']);
    }
}

function relay_session_dir(string $transferId): string {
    return RELAY_STORAGE_ROOT . DIRECTORY_SEPARATOR . $transferId;
}

function relay_meta_path(string $transferId): string {
    return relay_session_dir($transferId) . DIRECTORY_SEPARATOR . 'meta.json';
}

function relay_lock_path(string $transferId): string {
    return relay_session_dir($transferId) . DIRECTORY_SEPARATOR . 'session.lock';
}

function relay_uploaded_file_path(string $transferId): string {
    return relay_session_dir($transferId) . DIRECTORY_SEPARATOR . 'payload.bin';
}

function relay_safe_download_filename(string $filename): string {
    $filename = trim($filename);
    if ($filename === '') {
        return 'upload.zip';
    }

    $filename = basename($filename);
    $filename = str_replace(["\r", "\n", "\0"], '', $filename);
    $filename = preg_replace('/[^A-Za-z0-9._-]+/', '_', $filename) ?? 'upload.zip';

    if ($filename === '' || $filename === '.' || $filename === '..') {
        return 'upload.zip';
    }

    return $filename;
}

function relay_load_meta(string $transferId): array {
    $path = relay_meta_path($transferId);
    if (!is_file($path)) {
        return [];
    }
    $content = file_get_contents($path);
    if ($content === false || $content === '') {
        return [];
    }
    $decoded = json_decode($content, true);
    return is_array($decoded) ? $decoded : [];
}

function relay_save_meta(string $transferId, array $meta): void {
    $path = relay_meta_path($transferId);
    $tmp = $path . '.tmp';
    file_put_contents($tmp, json_encode($meta, JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT), LOCK_EX);
    rename($tmp, $path);
}

function relay_with_lock(string $transferId, callable $callback) {
    $dir = relay_session_dir($transferId);
    if (!is_dir($dir)) {
        @mkdir($dir, 0775, true);
    }

    $lockFile = relay_lock_path($transferId);
    $lockHandle = fopen($lockFile, 'c+');
    if ($lockHandle === false) {
        relay_json_response(500, ['error' => 'Failed to create lock']);
    }

    try {
        if (!flock($lockHandle, LOCK_EX)) {
            relay_json_response(500, ['error' => 'Failed to acquire lock']);
        }
        return $callback();
    } finally {
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
    }
}

function relay_cleanup_expired_sessions(): void {
    if (!is_dir(RELAY_STORAGE_ROOT)) {
        return;
    }

    $now = time();
    $dirs = scandir(RELAY_STORAGE_ROOT);
    if ($dirs === false) {
        return;
    }

    foreach ($dirs as $entry) {
        if ($entry === '.' || $entry === '..') {
            continue;
        }
        $sessionDir = RELAY_STORAGE_ROOT . DIRECTORY_SEPARATOR . $entry;
        if (!is_dir($sessionDir)) {
            continue;
        }

        $metaPath = $sessionDir . DIRECTORY_SEPARATOR . 'meta.json';
        $createdAt = 0;
        if (is_file($metaPath)) {
            $decoded = json_decode((string)file_get_contents($metaPath), true);
            if (is_array($decoded) && isset($decoded['created_at'])) {
                $createdAt = (int)$decoded['created_at'];
            }
        }
        if ($createdAt <= 0) {
            $createdAt = (int)@filemtime($sessionDir);
        }

        if ($createdAt > 0 && ($now - $createdAt) > RELAY_SESSION_TTL_SECONDS) {
            $files = scandir($sessionDir) ?: [];
            foreach ($files as $file) {
                if ($file === '.' || $file === '..') {
                    continue;
                }
                @unlink($sessionDir . DIRECTORY_SEPARATOR . $file);
            }
            @rmdir($sessionDir);
        }
    }
}

function relay_delete_session_directory(string $transferId): void {
    $sessionDir = relay_session_dir($transferId);
    if (!is_dir($sessionDir)) {
        return;
    }

    $files = scandir($sessionDir) ?: [];
    foreach ($files as $file) {
        if ($file === '.' || $file === '..') {
            continue;
        }
        @unlink($sessionDir . DIRECTORY_SEPARATOR . $file);
    }
    @rmdir($sessionDir);
}
