import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FileUploadComponent, formatBytes, matchesAccept } from './file-upload.component';

describe('FileUploadComponent', () => {
  let component: FileUploadComponent;
  let fixture: ComponentFixture<FileUploadComponent>;

  beforeEach(async () => {
    // Mock URL.createObjectURL and revokeObjectURL in JSDOM
    URL.createObjectURL = vi.fn(() => 'blob:mock-url');
    URL.revokeObjectURL = vi.fn();

    await TestBed.configureTestingModule({
      imports: [FileUploadComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(FileUploadComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the file upload component', () => {
    expect(component).toBeTruthy();
  });

  it('should format bytes accurately', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1024)).toBe('1.0 KB');
    expect(formatBytes(1024 * 1024 * 5)).toBe('5.0 MB');
  });

  it('should match accept extensions correctly', () => {
    const excelFile = new File([''], 'orders.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    const txtFile = new File([''], 'notes.txt', { type: 'text/plain' });

    expect(matchesAccept(excelFile, '.xlsx,.xls,.csv')).toBe(true);
    expect(matchesAccept(txtFile, '.xlsx,.xls,.csv')).toBe(false);
  });

  it('should accept valid files and emit filesAccepted', () => {
    const validFile = new File(['content'], 'test-orders.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    let emittedFiles: File[] = [];
    component.filesAccepted.subscribe((files) => (emittedFiles = files));

    component.commitFiles([validFile]);

    expect(component.files().length).toBe(1);
    expect(component.files()[0].name).toBe('test-orders.xlsx');
    expect(emittedFiles.length).toBe(1);
    expect(component.rejectionMessage()).toBeNull();
  });

  it('should reject invalid file types with an error message', () => {
    component.accept = '.xlsx,.csv';
    const invalidFile = new File(['content'], 'virus.exe', { type: 'application/x-msdownload' });

    component.commitFiles([invalidFile]);

    expect(component.files().length).toBe(0);
    expect(component.rejectionMessage()).toContain('no tienen un formato admitido');
  });

  it('should remove a file from the files list and emit fileRemoved', () => {
    const file = new File(['data'], 'sample.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    component.commitFiles([file]);

    const item = component.files()[0];
    let removedItem: any = null;
    component.fileRemoved.subscribe((f) => (removedItem = f));

    component.removeFile(item, new MouseEvent('click'));

    expect(component.files().length).toBe(0);
    expect(removedItem.id).toBe(item.id);
  });
});
