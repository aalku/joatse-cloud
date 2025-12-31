// Get the base URL from window.location
const baseUrl = window.location.pathname.endsWith('/') 
    ? window.location.pathname 
    : window.location.pathname + '/';

let currentPath = '/';

function canOpenInBrowser(mimeType) {
    if (!mimeType) return false;
    const mt = mimeType.toLowerCase();
    return mt.startsWith('text/')
        || mt.startsWith('image/')
        || mt.startsWith('audio/')
        || mt.startsWith('video/')
        || mt === 'application/pdf'
        || mt === 'application/json'
        || mt === 'application/xml';
}

// Format file size
function formatSize(bytes) {
    if (bytes === 0) return '-';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
}

// Format date
function formatDate(timestamp) {
    if (!timestamp) return '-';
    const date = new Date(timestamp);
    return date.toLocaleString();
}

// Update breadcrumb
function updateBreadcrumb(path) {
    const breadcrumb = document.getElementById('breadcrumb');
    breadcrumb.innerHTML = '';
    
    const parts = path.split('/').filter(p => p);
    
    // Root
    const rootLi = document.createElement('li');
    rootLi.className = 'breadcrumb-item';
    if (parts.length === 0) {
        rootLi.classList.add('active');
        rootLi.textContent = 'Root';
    } else {
        const rootLink = document.createElement('a');
        rootLink.href = '#';
        rootLink.textContent = 'Root';
        rootLink.dataset.path = '/';
        rootLink.onclick = (e) => {
            e.preventDefault();
            navigateTo('/');
        };
        rootLi.appendChild(rootLink);
    }
    breadcrumb.appendChild(rootLi);
    
    // Path parts
    let accPath = '';
    parts.forEach((part, index) => {
        accPath += '/' + part;
        const li = document.createElement('li');
        li.className = 'breadcrumb-item';
        
        if (index === parts.length - 1) {
            li.classList.add('active');
            li.textContent = part;
        } else {
            const link = document.createElement('a');
            link.href = '#';
            link.textContent = part;
            link.dataset.path = accPath;
            link.onclick = (e) => {
                e.preventDefault();
                navigateTo(accPath);
            };
            li.appendChild(link);
        }
        breadcrumb.appendChild(li);
    });
}

// Load directory listing
async function loadDirectory(path) {
    const loading = document.getElementById('loading');
    const error = document.getElementById('error');
    const fileList = document.getElementById('file-list');
    
    loading.style.display = 'block';
    error.style.display = 'none';
    fileList.innerHTML = '';
    
    try {
        const response = await fetch(`${baseUrl}api/list?path=${encodeURIComponent(path)}`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }
        
        const data = await response.json();
        loading.style.display = 'none';
        
        const items = data.items || data.entries || [];
        
        if (items.length === 0) {
            fileList.innerHTML = '<tr><td colspan="4" class="text-center text-muted">Empty folder</td></tr>';
            return;
        }
        
        // Sort: directories first, then files
        items.sort((a, b) => {
            const aIsDir = a.type === 'directory' || a.isDirectory;
            const bIsDir = b.type === 'directory' || b.isDirectory;
            if (aIsDir !== bIsDir) {
                return aIsDir ? -1 : 1;
            }
            return a.name.localeCompare(b.name);
        });
        
        items.forEach(entry => {
            const isDirectory = entry.type === 'directory' || entry.isDirectory;
            const row = document.createElement('tr');
            row.className = 'file-row';
            const filePath = path === '/' ? `/${entry.name}` : `${path}/${entry.name}`;

            // Name column
            const nameCell = document.createElement('td');
            const icon = isDirectory ? '📁' : '📄';
            const nameLink = document.createElement('a');
            nameLink.href = '#';
            nameLink.innerHTML = `${icon} ${escapeHtml(entry.name)}`;
            // Only show pointer cursor if clickable
            if (isDirectory) {
                nameLink.style.cursor = 'pointer';
                nameLink.onclick = (e) => {
                    e.preventDefault();
                    let newPath;
                    if (entry.name === '..') {
                        // Go to parent directory
                        const parts = path.split('/').filter(p => p);
                        parts.pop();
                        newPath = parts.length > 0 ? '/' + parts.join('/') : '/';
                    } else {
                        // Navigate into subdirectory
                        newPath = path === '/' ? `/${entry.name}` : `${path}/${entry.name}`;
                    }
                    navigateTo(newPath);
                };
            } else {
                nameLink.style.cursor = 'default';
                nameLink.onclick = null;
            }
            nameCell.appendChild(nameLink);
            row.appendChild(nameCell);

            // Size column
            const sizeCell = document.createElement('td');
            sizeCell.textContent = isDirectory ? '-' : formatSize(entry.size);
            row.appendChild(sizeCell);

            // Modified column
            const modCell = document.createElement('td');
            modCell.textContent = formatDate(entry.lastModified);
            row.appendChild(modCell);

            // Actions column
            const actionsCell = document.createElement('td');
            // Download button for files
            if (!isDirectory) {
                const downloadUrl = `${baseUrl}api/download?path=${encodeURIComponent(filePath)}`;
                if (canOpenInBrowser(entry.mimeType)) {
                    const openBtn = document.createElement('a');
                    openBtn.href = downloadUrl;
                    openBtn.target = '_blank';
                    openBtn.rel = 'noopener noreferrer';
                    openBtn.className = 'btn btn-sm btn-outline-secondary me-1';
                    openBtn.textContent = 'Open';
                    actionsCell.appendChild(openBtn);
                }
                const downloadBtn = document.createElement('a');
                downloadBtn.href = downloadUrl;
                downloadBtn.className = 'btn btn-sm btn-outline-primary me-1';
                downloadBtn.textContent = 'Download';
                downloadBtn.setAttribute('download', entry.name);
                actionsCell.appendChild(downloadBtn);
            }
            // Rename button for files and folders (not ..) when writable
            if (entry.name !== '..' && !window.readOnly) {
                const renameBtn = document.createElement('button');
                renameBtn.className = 'btn btn-sm btn-outline-secondary me-1';
                renameBtn.textContent = 'Rename';
                renameBtn.onclick = async () => {
                    const newName = prompt('Enter new name:', entry.name);
                    if (!newName || newName === entry.name) return;
                    const fromPath = filePath;
                    const destPath = path === '/' ? `/${newName}` : `${path}/${newName}`;
                    const moveUrl = `${baseUrl}api/move?path=${encodeURIComponent(fromPath)}`;
                    const resp = await fetch(moveUrl, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ path: destPath })
                    });
                    if (resp.ok) {
                        loadDirectory(path);
                    } else {
                        alert(`Rename failed: ${resp.status} ${await resp.text()}`);
                    }
                };
                actionsCell.appendChild(renameBtn);
            }
            // Delete button for files and folders (not ..)
            if (entry.name !== '..' && !window.readOnly) {
                const deleteBtn = document.createElement('button');
                deleteBtn.className = 'btn btn-sm btn-outline-danger';
                deleteBtn.textContent = 'Delete';
                deleteBtn.onclick = async () => {
                    if (confirm(`Delete ${entry.name}? This cannot be undone.`)) {
                        const api = isDirectory ? 'rmdir' : 'delete';
                        const delUrl = `${baseUrl}api/${api}?path=${encodeURIComponent(path === '/' ? `/${entry.name}` : `${path}/${entry.name}`)}`;
                        const resp = await fetch(delUrl, { method: 'DELETE' });
                        if (resp.ok) {
                            loadDirectory(path);
                        } else {
                            alert(`Delete failed: ${resp.status} ${await resp.text()}`);
                        }
                    }
                };
                actionsCell.appendChild(deleteBtn);
            }
            row.appendChild(actionsCell);

            fileList.appendChild(row);
        });
        
    } catch (err) {
        loading.style.display = 'none';
        error.style.display = 'block';
        error.textContent = `Error loading directory: ${err.message}`;
    }
}

// Navigate to a path
function navigateTo(path) {
    currentPath = path;
    updateBreadcrumb(path);
    loadDirectory(path);
}

// Escape HTML
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Initialize on page load

document.addEventListener('DOMContentLoaded', async function() {
    // Check readOnly status once on load
    window.readOnly = false;
    try {
        const metaResp = await fetch(`${baseUrl}api/metadata`);
        if (metaResp.ok) {
            const meta = await metaResp.json();
            window.readOnly = !!meta.readOnly;
        }
    } catch (e) {
        // If metadata fails, default to not readOnly (allow UI, fail on server)
        console.warn('Could not fetch folder metadata:', e);
    }

    // Setup upload button
    const uploadBtn = document.getElementById('upload-btn');
    const uploadFileInput = document.getElementById('upload-file-input');
    const newFolderBtn = document.getElementById('new-folder-btn');

    if (window.readOnly) {
        // Hide upload and new folder buttons if readOnly
        uploadBtn.style.display = 'none';
        newFolderBtn.style.display = 'none';
    } else {
        uploadBtn.onclick = () => {
            uploadFileInput.click();
        };
        uploadFileInput.onchange = () => {
            const files = uploadFileInput.files;
            if (files.length > 0) {
                handleFilesSelected(files);
            }
            // Reset input so the same file can be selected again
            uploadFileInput.value = '';
        };
        newFolderBtn.onclick = async () => {
            const name = prompt('Enter new folder name:');
            if (!name) return;
            const folderPath = currentPath === '/' ? `/${name}` : `${currentPath}/${name}`;
            const mkdirUrl = `${baseUrl}api/mkdir?path=${encodeURIComponent(folderPath)}`;
            const resp = await fetch(mkdirUrl, { method: 'PUT' });
            if (resp.ok) {
                loadDirectory(currentPath);
            } else {
                alert(`Create folder failed: ${resp.status} ${await resp.text()}`);
            }
        };
    }

    navigateTo('/');
});

// Handle files selected for upload
async function handleFilesSelected(files) {
    console.log(`Selected ${files.length} file(s) for upload to ${currentPath}:`);
    
    for (const file of files) {
        console.log(`  - ${file.name} (${formatSize(file.size)})`);
        await uploadFile(file);
    }
    
    // Refresh directory listing after all uploads complete
    loadDirectory(currentPath);
}

// Upload a single file
async function uploadFile(file) {
    const filePath = currentPath === '/' ? `/${file.name}` : `${currentPath}/${file.name}`;
    const uploadUrl = `${baseUrl}api/upload?path=${encodeURIComponent(filePath)}`;
    
    console.log(`Uploading ${file.name} to ${filePath}...`);
    
    try {
        const response = await fetch(uploadUrl, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/octet-stream',
                'Content-Length': file.size.toString()
            },
            body: file  // Browser streams the file automatically
        });
        
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`HTTP ${response.status}: ${errorText}`);
        }
        
        const result = await response.json();
        console.log(`Upload complete: ${file.name}`, result);
        
    } catch (err) {
        console.error(`Upload failed for ${file.name}:`, err);
        alert(`Upload failed for ${file.name}: ${err.message}`);
        throw err; // Re-throw to stop processing remaining files
    }
}
