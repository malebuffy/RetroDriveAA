<?php

declare(strict_types=1);
require_once __DIR__ . '/common.php';

function relay_parse_size_to_bytes(string $size): int {
    $size = trim($size);
    if ($size === '') return 0;
    $unit = strtolower(substr($size, -1));
    $value = (float)$size;
    return match ($unit) {
        'g' => (int)($value * 1024 * 1024 * 1024),
        'm' => (int)($value * 1024 * 1024),
        'k' => (int)($value * 1024),
        default => (int)$value,
    };
}

function relay_upload_error_message(int $code): string {
    return match ($code) {
        UPLOAD_ERR_INI_SIZE, UPLOAD_ERR_FORM_SIZE => 'Upload failed: file is too large.',
        UPLOAD_ERR_PARTIAL => 'Upload failed: upload was interrupted.',
        UPLOAD_ERR_NO_FILE => 'Upload failed: no file was selected.',
        UPLOAD_ERR_NO_TMP_DIR, UPLOAD_ERR_CANT_WRITE, UPLOAD_ERR_EXTENSION => 'Upload failed due to a server error.',
        default => 'Upload failed. Please try again.',
    };
}

function relay_is_ajax_request(): bool {
    $requestedWith = strtolower((string)($_SERVER['HTTP_X_REQUESTED_WITH'] ?? ''));
    $accept = strtolower((string)($_SERVER['HTTP_ACCEPT'] ?? ''));
    return $requestedWith === 'xmlhttprequest' || str_contains($accept, 'application/json');
}

function relay_render_upload_page(
    string $transferId,
    string $token,
    ?string $resultType = null,
    ?string $resultTitle = null,
    ?string $resultMessage = null,
    bool $uploadEnabled = true
): void {
    $safeAction = htmlspecialchars("upload.php?id={$transferId}&token={$token}", ENT_QUOTES, 'UTF-8');
    $safeTitle = htmlspecialchars($resultTitle ?? '', ENT_QUOTES, 'UTF-8');
    $safeMessage = htmlspecialchars($resultMessage ?? '', ENT_QUOTES, 'UTF-8');
    $resultType = $resultType ?? 'none';

    echo "<!doctype html>\n";
    echo "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>";
    echo "<title>RetroDrive Upload</title>";
    echo "<style>";
    echo ":root{--bg:#0b1020;--card:#121a33;--accent:#1B5E20;--accent2:#4CAF50;--danger:#d32f2f;--muted:#b0bec5;--text:#e8f5e9;}";
    echo "*{box-sizing:border-box}body{font-family:Arial,sans-serif;margin:0;background:radial-gradient(circle at top,#1a284a,var(--bg));color:var(--text);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}";
    echo ".card{width:min(760px,96vw);background:var(--card);border:1px solid #2d3b66;border-radius:18px;padding:24px;box-shadow:0 12px 36px rgba(0,0,0,.35)}";
    echo ".brand{font-size:30px;font-weight:800;color:var(--accent2);letter-spacing:.5px;margin:0 0 8px} .subtitle{margin:0 0 18px;color:var(--muted);line-height:1.45}";
    echo ".panel{background:#0f1730;border:1px solid #24345f;border-radius:14px;padding:16px;margin-top:14px}";
    echo "input[type=file]{width:100%;padding:12px;background:#0a1125;border:1px solid #2a3e70;border-radius:10px;color:var(--text)}";
    echo "button{margin-top:14px;width:100%;padding:14px 18px;border:0;border-radius:10px;background:linear-gradient(90deg,var(--accent),var(--accent2));color:#fff;font-weight:700;font-size:16px;cursor:pointer}";
    echo "button:disabled{opacity:.65;cursor:not-allowed}";
    echo ".progress-wrap{display:none;margin-top:14px} .progress-bg{height:18px;background:#19294d;border-radius:999px;overflow:hidden;border:1px solid #2f467c}";
    echo ".progress-bar{height:100%;width:0;background:linear-gradient(90deg,#2e7d32,#66bb6a);transition:width .2s ease}";
    echo ".progress-text{text-align:center;margin-top:8px;color:#c8e6c9;font-weight:700}";
    echo ".result{display:none;margin-top:16px;padding:14px;border-radius:12px;border:1px solid transparent}";
    echo ".result.success{display:block;background:rgba(76,175,80,.16);border-color:rgba(76,175,80,.4)}";
    echo ".result.error{display:block;background:rgba(211,47,47,.16);border-color:rgba(211,47,47,.45)}";
    echo ".result h3{margin:0 0 6px;font-size:18px} .result p{margin:0;color:#d7e3ff}";
    echo ".disabled-note{display:block;margin-top:10px;padding:10px;border-radius:10px;background:rgba(211,47,47,.14);border:1px solid rgba(211,47,47,.35);color:#ffcdd2}";
    echo "small.hint{display:block;margin-top:12px;color:#90a4ae}";
    echo "</style></head><body>";
    echo "<div class='card'>";
    echo "<h1 class='brand'>RetroDrive</h1>";
    echo "<p class='subtitle'>Upload a ZIP file from this device. Your car will detect and download it automatically.</p>";
    echo "<div class='panel'>";
    if ($resultType !== 'success') {
        echo "<form id='uploadForm' method='POST' enctype='multipart/form-data' action='{$safeAction}'>";
        $disabledAttr = $uploadEnabled ? '' : ' disabled';
        echo "<input type='file' name='file' id='fileInput' accept='.zip,.ZIP' required{$disabledAttr}>";
        echo "<button type='submit' id='uploadBtn'{$disabledAttr}>Upload to RetroDrive</button>";
        echo "<div class='progress-wrap' id='progressWrap'><div class='progress-bg'><div class='progress-bar' id='progressBar'></div></div><div class='progress-text' id='progressText'>0%</div></div>";
        if (!$uploadEnabled) {
            echo "<div class='disabled-note'>Upload is not ready yet. Please start Remote File Transfer from the RetroDrive app and scan the new QR code.</div>";
        }
        echo "</form>";
    }

    if ($resultType !== 'none') {
        $class = $resultType === 'success' ? 'success' : 'error';
        echo "<div class='result {$class}' id='resultBox'><h3>{$safeTitle}</h3><p>{$safeMessage}</p></div>";
    } else {
        echo "<div class='result' id='resultBox'><h3 id='resultTitle'></h3><p id='resultMessage'></p></div>";
    }

    echo "</div></div>";

    echo "<script>";
    echo "const form=document.getElementById('uploadForm');const btn=document.getElementById('uploadBtn');const fileInput=document.getElementById('fileInput');const wrap=document.getElementById('progressWrap');const bar=document.getElementById('progressBar');const txt=document.getElementById('progressText');const box=document.getElementById('resultBox');const title=document.getElementById('resultTitle');const msg=document.getElementById('resultMessage');";
    echo "function showResult(ok,t,m){box.style.display='block';box.className='result '+(ok?'success':'error');if(title)title.textContent=t;if(msg)msg.textContent=m;}";
    echo "function lockUploadControls(){if(fileInput){fileInput.disabled=true;fileInput.style.display='none';}if(btn){btn.disabled=true;btn.style.display='none';}if(form){form.style.pointerEvents='none';}}";
    echo "form?.addEventListener('submit',function(e){e.preventDefault();if(!fileInput.files.length){showResult(false,'No file selected','Please choose a ZIP file first.');return;}const data=new FormData(form);const xhr=new XMLHttpRequest();btn.disabled=true;wrap.style.display='block';bar.style.width='0%';txt.textContent='0%';box.style.display='none';xhr.upload.addEventListener('progress',function(ev){if(!ev.lengthComputable)return;const p=Math.max(0,Math.min(100,Math.round((ev.loaded/ev.total)*100)));bar.style.width=p+'%';txt.textContent=p+'%';});xhr.addEventListener('load',function(){btn.disabled=false;let payload=null;try{payload=JSON.parse(xhr.responseText);}catch(_){ }if(xhr.status>=200&&xhr.status<300){bar.style.width='100%';txt.textContent='100%';showResult(true,'Upload complete','File uploaded successfully. You can return to your car screen now.');fileInput.value='';lockUploadControls();}else{const serverMsg=(payload&&payload.error)?payload.error:'Upload failed with HTTP '+xhr.status;showResult(false,'Upload failed',serverMsg);}});xhr.addEventListener('error',function(){btn.disabled=false;showResult(false,'Network error','Connection failed during upload. Please try again.');});xhr.addEventListener('timeout',function(){btn.disabled=false;showResult(false,'Upload timed out','The upload took too long. Please retry.');});xhr.open('POST',form.action,true);xhr.timeout=180000;xhr.setRequestHeader('X-Requested-With','XMLHttpRequest');xhr.setRequestHeader('Accept','application/json');xhr.send(data);});";
    echo "</script></body></html>";
    exit;
}

relay_cleanup_expired_sessions();

$transferId = relay_get_transfer_id();
$token = relay_get_token();

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $uploadEnabled = relay_with_lock($transferId, function () use ($transferId, $token): bool {
        $meta = relay_load_meta($transferId);
        if (empty($meta)) {
            return false;
        }

        if (($meta['token'] ?? '') !== $token) {
            return false;
        }

        $status = (string)($meta['status'] ?? 'waiting');
        return $status === 'waiting';
    });

    relay_render_upload_page($transferId, $token, null, null, null, $uploadEnabled);
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    if (relay_is_ajax_request()) {
        relay_json_response(405, ['error' => 'Method not allowed']);
    }
    relay_render_upload_page($transferId, $token, 'error', 'Invalid request', 'Only GET and POST methods are supported.');
}

if (!isset($_FILES['file']) || !is_array($_FILES['file'])) {
    if (relay_is_ajax_request()) {
        relay_json_response(400, ['error' => 'No file provided']);
    }
    relay_render_upload_page($transferId, $token, 'error', 'No file provided', 'Please select a ZIP file and try again.');
}

$file = $_FILES['file'];
if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
    $code = (int)($file['error'] ?? -1);
    $errorPayload = [
        'error' => relay_upload_error_message($code),
        'code' => $code,
        'limits' => [
            'upload_max_filesize' => (string)ini_get('upload_max_filesize'),
            'post_max_size' => (string)ini_get('post_max_size'),
            'upload_max_filesize_bytes' => relay_parse_size_to_bytes((string)ini_get('upload_max_filesize')),
            'post_max_size_bytes' => relay_parse_size_to_bytes((string)ini_get('post_max_size')),
            'relay_max_file_size_bytes' => RELAY_MAX_FILE_SIZE,
        ],
    ];
    if (relay_is_ajax_request()) {
        relay_json_response(400, $errorPayload);
    }
    relay_render_upload_page($transferId, $token, 'error', 'Upload failed', $errorPayload['error']);
}

$size = (int)($file['size'] ?? 0);
if ($size <= 0 || $size > RELAY_MAX_FILE_SIZE) {
    if (relay_is_ajax_request()) {
        relay_json_response(413, ['error' => 'File too large or invalid size']);
    }
    relay_render_upload_page($transferId, $token, 'error', 'Invalid file size', 'The uploaded file is too large or empty.');
}

$originalName = (string)($file['name'] ?? 'upload.zip');
if (!preg_match('/\.zip$/i', $originalName)) {
    if (relay_is_ajax_request()) {
        relay_json_response(400, ['error' => 'Only .zip files are allowed']);
    }
    relay_render_upload_page($transferId, $token, 'error', 'Invalid file type', 'Only .zip files are allowed.');
}

relay_with_lock($transferId, function () use ($transferId, $token, $file, $size, $originalName): void {
    $meta = relay_load_meta($transferId);

    if (empty($meta)) {
        relay_json_response(404, ['error' => 'Transfer session not initialized']);
    }

    if (isset($meta['token']) && $meta['token'] !== $token) {
        relay_json_response(403, ['error' => 'Token mismatch for this transfer id']);
    }

    $currentStatus = (string)($meta['status'] ?? 'waiting');
    if ($currentStatus === 'ready' || $currentStatus === 'downloading' || $currentStatus === 'consumed') {
        relay_json_response(409, ['error' => 'Transfer is not accepting uploads', 'status' => $currentStatus]);
    }

    $targetPath = relay_uploaded_file_path($transferId);
    $tmpPath = $targetPath . '.uploading';

    if (is_file($tmpPath)) {
        @unlink($tmpPath);
    }

    if (!move_uploaded_file((string)$file['tmp_name'], $tmpPath)) {
        relay_json_response(500, ['error' => 'Failed to persist upload']);
    }

    if (is_file($targetPath)) {
        @unlink($targetPath);
    }
    if (!rename($tmpPath, $targetPath)) {
        @unlink($tmpPath);
        relay_json_response(500, ['error' => 'Failed to finalize upload']);
    }

    $meta = [
        'transfer_id' => $transferId,
        'token' => $token,
        'status' => 'ready',
        'filename' => $originalName,
        'size' => $size,
        'created_at' => isset($meta['created_at']) ? (int)$meta['created_at'] : time(),
        'updated_at' => time(),
    ];
    relay_save_meta($transferId, $meta);
});

if (relay_is_ajax_request()) {
    relay_json_response(200, ['ok' => true, 'status' => 'ready']);
}

relay_render_upload_page(
    $transferId,
    $token,
    'success',
    'Upload complete',
    'Your file is uploaded. Return to the car screen; RetroDrive will pull it automatically.'
);
