<?php

declare(strict_types=1);
require_once __DIR__ . '/common.php';

relay_cleanup_expired_sessions();

$transferId = relay_get_transfer_id();
$token = relay_get_token();

$downloadData = relay_with_lock($transferId, function () use ($transferId, $token): array {
    $meta = relay_load_meta($transferId);
    if (empty($meta)) {
        relay_json_response(404, ['error' => 'Transfer not found']);
    }

    if (($meta['token'] ?? '') !== $token) {
        relay_json_response(403, ['error' => 'Token mismatch']);
    }

    if (($meta['status'] ?? '') !== 'ready') {
        relay_json_response(409, ['error' => 'Transfer is not ready', 'status' => $meta['status'] ?? 'unknown']);
    }

    $filePath = relay_uploaded_file_path($transferId);
    if (!is_file($filePath)) {
        relay_json_response(404, ['error' => 'Uploaded file missing']);
    }

    $meta['status'] = 'downloading';
    $meta['updated_at'] = time();
    relay_save_meta($transferId, $meta);

    return [
        'file_path' => $filePath,
        'filename' => relay_safe_download_filename((string)($meta['filename'] ?? 'upload.zip')),
        'size' => (int)filesize($filePath),
    ];
});

$filePath = $downloadData['file_path'];
$filename = $downloadData['filename'];
$size = $downloadData['size'];

header('Content-Type: application/octet-stream');
header('Content-Length: ' . $size);
header('Content-Disposition: attachment; filename="' . addslashes($filename) . '"');
header('Cache-Control: no-store, no-cache, must-revalidate');

$fp = fopen($filePath, 'rb');
if ($fp === false) {
    relay_json_response(500, ['error' => 'Cannot open file for reading']);
}

while (!feof($fp)) {
    $chunk = fread($fp, 8192);
    if ($chunk === false) {
        break;
    }
    echo $chunk;
    flush();
}
fclose($fp);

$aborted = connection_aborted();

relay_with_lock($transferId, function () use ($transferId, $filePath, $aborted): void {
    $meta = relay_load_meta($transferId);

    if ($aborted) {
        $meta['status'] = 'ready';
        $meta['updated_at'] = time();
        relay_save_meta($transferId, $meta);
        return;
    }

    // Successful one-time download: delete file after app receives it.
    if (is_file($filePath)) {
        @unlink($filePath);
    }

    $meta['status'] = 'consumed';
    $meta['updated_at'] = time();
    $meta['downloaded_at'] = time();
    relay_save_meta($transferId, $meta);

    // Fully erase consumed transfer traces immediately.
    relay_delete_session_directory($transferId);
});
