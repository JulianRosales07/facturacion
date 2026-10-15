import { Component, OnInit, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../../../../core/services/api.service';
import { FileDownloadService } from '../../../../core/services/file-download.service';
@Component({
  selector: 'app-invoice-graphic',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './invoice-graphic.component.html',
  styleUrls: ['../../../../shared/styles/received-pages.scss', '../invoice-list/invoice-list-grouped.scss']
})
export class InvoiceGraphicComponent implements OnInit {
  private readonly api=inject(ApiService);
  private readonly route=inject(ActivatedRoute);
  private readonly sanitizer=inject(DomSanitizer);
  private readonly downloads=inject(FileDownloadService);
  private readonly destroy=inject(DestroyRef);
  private objectUrl='';
  invoiceId='';
  readonly pdf=signal<SafeResourceUrl|null>(null);
  readonly error=signal('');
  readonly loading=signal(true);
  ngOnInit():void{
    this.destroy.onDestroy(()=>this.release());
    this.route.paramMap.pipe(switchMap(params=>{
      this.release();this.pdf.set(null);this.loading.set(true);this.error.set('');this.invoiceId=params.get('id')!;
      return this.api.getBlob(`/facturas/${this.invoiceId}/pdf`);
    }),takeUntilDestroyed(this.destroy)).subscribe({next:blob=>{
      this.loading.set(false);
      if(!blob.type.includes('application/pdf')){this.error.set('El servidor no devolvió un PDF válido.');return;}
      this.objectUrl=URL.createObjectURL(blob);
      this.pdf.set(this.sanitizer.bypassSecurityTrustResourceUrl(this.objectUrl));
    },error:err=>{this.loading.set(false);this.error.set(err.status===404?'El PDF oficial todavía no está disponible. Revise la sincronización.':'No se pudo abrir el PDF. Revise su sesión y vuelva a intentarlo.');}});
  }
  download():void{this.downloads.download(`/facturas/${this.invoiceId}/pdf`,`factura-${this.invoiceId}.pdf`);}
  private release():void{if(this.objectUrl){URL.revokeObjectURL(this.objectUrl);this.objectUrl='';}}
}
