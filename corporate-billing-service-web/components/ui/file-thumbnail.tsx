import * as React from "react";
import { cn } from "@/lib/utils";

export interface FileThumbnailProps extends React.HTMLAttributes<HTMLDivElement> {
  file: {
    name: string;
    type: string;
  };
  previewImageUrl?: string | null;
}

export function FileThumbnail({
  file,
  previewImageUrl,
  className,
  ...props
}: FileThumbnailProps) {
  if (previewImageUrl) {
    return (
      <div
        className={cn(
          "relative overflow-hidden rounded-lg border bg-muted flex items-center justify-center",
          className
        )}
        {...props}
      >
        <img
          src={previewImageUrl}
          alt={file.name}
          className="h-full w-full object-cover"
        />
      </div>
    );
  }

  const isSpreadsheet = file.name.endsWith(".xlsx") || file.name.endsWith(".xls") || file.name.endsWith(".csv");
  const isPdf = file.name.endsWith(".pdf");

  return (
    <div
      className={cn(
        "flex items-center justify-center rounded-lg border bg-muted text-xs font-bold uppercase tracking-wider text-muted-foreground",
        isSpreadsheet && "bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-800",
        isPdf && "bg-red-50 text-red-700 border-red-200 dark:bg-red-950/40 dark:text-red-400 dark:border-red-800",
        className
      )}
      {...props}
    >
      {file.name.split(".").pop() || "FILE"}
    </div>
  );
}
