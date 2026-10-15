export interface UploadFileItem {
  id: string;
  name: string;
  type: string;
  size: number;
  url: string;
  rawFile: File;
  extension: string;
}

export interface AcceptedFileTypeConfig {
  label: string;
  iconName: string;
  color?: string;
}
