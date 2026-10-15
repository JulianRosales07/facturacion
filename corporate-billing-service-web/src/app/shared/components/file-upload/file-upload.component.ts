import {
  Component,
  Input,
  Output,
  EventEmitter,
  signal,
  ElementRef,
  ViewChild,
  ChangeDetectionStrategy,
  OnDestroy
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { UploadFileItem, AcceptedFileTypeConfig } from './file-upload.types';

export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / Math.pow(1024, index)).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

export function matchesAccept(file: File, accept?: string): boolean {
  if (!accept) return true;
  return accept.split(',').some((rawToken) => {
    const token = rawToken.trim().toLowerCase();
    if (!token) return false;
    if (token.startsWith('.')) return file.name.toLowerCase().endsWith(token);
    if (token.endsWith('/*')) {
      return file.type.toLowerCase().startsWith(token.slice(0, -1));
    }
    return file.type.toLowerCase() === token;
  });
}

@Component({
  selector: 'app-file-upload',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './file-upload.component.html',
  styleUrls: ['./file-upload.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class FileUploadComponent implements OnDestroy {
  @Input() accept = '.pdf,.doc,.docx,.xlsx,.xls,.csv,.png,.jpg,.jpeg,.json,.txt,.tsv';
  @Input() multiple = true;
  @Input() maxSizeBytes = 25 * 1024 * 1024; // 25 MB default
  @Input() title = 'Haga clic o arrastre archivos aquí';
  @Input() description = 'Formatos admitidos: Excel (.xlsx, .xls), CSV, PDF, Imágenes, JSON';
  @Input() browseLabel = 'Examinar archivos';
  @Input() draggingLabel = 'Suelte para agregar';
  @Input() showFileList = true;
  @Input() showBorderBeam = true;

  @Output() filesAccepted = new EventEmitter<File[]>();
  @Output() fileRemoved = new EventEmitter<UploadFileItem>();

  @ViewChild('fileInput') fileInputRef?: ElementRef<HTMLInputElement>;

  private dragDepth = 0;
  readonly isDragging = signal(false);
  readonly files = signal<UploadFileItem[]>([]);
  readonly rejectionMessage = signal<string | null>(null);

  readonly iconCards: AcceptedFileTypeConfig[] = [
    { label: 'Excel / Hoja', iconName: 'table', color: '#10b981' },
    { label: 'PDF Documento', iconName: 'pdf', color: '#ef4444' },
    { label: 'Imagen / Datos', iconName: 'image', color: '#3b82f6' }
  ];

  openFileDialog(): void {
    this.fileInputRef?.nativeElement?.click();
  }

  onDragEnter(event: DragEvent): void {
    event.preventDefault();
    this.dragDepth++;
    this.isDragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragDepth = Math.max(0, this.dragDepth - 1);
    if (this.dragDepth === 0) {
      this.isDragging.set(false);
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragDepth = 0;
    this.isDragging.set(false);

    if (event.dataTransfer?.files?.length) {
      this.commitFiles(event.dataTransfer.files);
    }
  }

  onFileInputChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files?.length) {
      this.commitFiles(input.files);
      input.value = '';
    }
  }

  commitFiles(fileList: FileList | File[]): void {
    const rawFiles = Array.from(fileList);
    const validFiles: File[] = [];

    let hasRejected = false;
    let hasSizeRejected = false;

    for (const f of rawFiles) {
      if (!matchesAccept(f, this.accept)) {
        hasRejected = true;
        continue;
      }
      if (f.size > this.maxSizeBytes) {
        hasSizeRejected = true;
        continue;
      }
      validFiles.push(f);
      if (!this.multiple) break;
    }

    if (hasRejected) {
      this.rejectionMessage.set('Uno o más archivos no tienen un formato admitido.');
    } else if (hasSizeRejected) {
      this.rejectionMessage.set(`El archivo supera el tamaño máximo permitido (${formatBytes(this.maxSizeBytes)}).`);
    } else {
      this.rejectionMessage.set(null);
    }

    if (validFiles.length === 0) return;

    const newItems: UploadFileItem[] = validFiles.map((file) => {
      const ext = file.name.split('.').pop()?.toUpperCase() || 'FILE';
      return {
        id: `${file.name}-${file.size}-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`,
        name: file.name,
        type: file.type || 'Archivo de datos',
        size: file.size,
        url: URL.createObjectURL(file),
        rawFile: file,
        extension: ext
      };
    });

    if (this.multiple) {
      this.files.update((prev) => [...prev, ...newItems]);
    } else {
      this.files().forEach((f) => URL.revokeObjectURL(f.url));
      this.files.set(newItems);
    }

    this.filesAccepted.emit(validFiles);
  }

  removeFile(item: UploadFileItem, event: MouseEvent): void {
    event.stopPropagation();
    URL.revokeObjectURL(item.url);
    this.files.update((prev) => prev.filter((f) => f.id !== item.id));
    this.fileRemoved.emit(item);
  }

  formatSize(bytes: number): string {
    return formatBytes(bytes);
  }

  ngOnDestroy(): void {
    this.files().forEach((file) => URL.revokeObjectURL(file.url));
  }
}
