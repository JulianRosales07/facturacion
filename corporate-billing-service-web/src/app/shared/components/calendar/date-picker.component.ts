import {
  Component,
  Input,
  Output,
  EventEmitter,
  forwardRef,
  signal,
  computed,
  ElementRef,
  HostListener,
  inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, FormsModule } from '@angular/forms';
import { CalendarComponent } from './calendar.component';
import { CalendarMode, DateRange } from './calendar.types';

export function formatDateToISO(d?: Date): string {
  if (!d) return '';
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

@Component({
  selector: 'app-date-picker',
  standalone: true,
  imports: [CommonModule, FormsModule, CalendarComponent],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => DatePickerComponent),
      multi: true
    }
  ],
  template: `
    <div class="date-picker-wrapper">
      <div
        class="date-picker-input-container"
        (click)="togglePopover()"
        [class.date-picker-input-container--open]="isOpen()"
        [class.date-picker-input-container--disabled]="disabled"
      >
        <svg class="calendar-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect>
          <line x1="16" y1="2" x2="16" y2="6"></line>
          <line x1="8" y1="2" x2="8" y2="6"></line>
          <line x1="3" y1="10" x2="18" y2="10"></line>
        </svg>

        <span class="display-value" [class.display-value--placeholder]="!displayValue()">
          {{ displayValue() || placeholder }}
        </span>

        @if (displayValue() && !disabled) {
          <button
            type="button"
            class="clear-btn"
            (click)="clear($event)"
            title="Limpiar fecha"
          >
            &times;
          </button>
        }
      </div>

      @if (isOpen()) {
        <div class="date-picker-popover">
          <app-calendar
            [mode]="mode"
            [selected]="currentSelected()"
            [quickNav]="true"
            (select)="onCalendarSelect($event)"
          ></app-calendar>
        </div>
      }
    </div>
  `,
  styles: [`
    :host {
      display: inline-block;
      width: 100%;
    }

    .date-picker-wrapper {
      position: relative;
      width: 100%;
    }

    .date-picker-input-container {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.45rem 0.75rem;
      border: 1px solid var(--cb-border-subtle, #d1d5db);
      border-radius: 7px;
      background: #ffffff;
      cursor: pointer;
      font-size: 0.85rem;
      color: var(--cb-text-primary, #0f172a);
      transition: all 0.15s ease;

      &:hover:not(&--disabled) {
        border-color: var(--cb-border-strong, #94a3b8);
      }

      &--open {
        border-color: var(--cb-primary, #0f4c81);
        box-shadow: 0 0 0 3px rgba(15, 76, 129, 0.12);
      }

      &--disabled {
        background-color: #f8fafc;
        cursor: not-allowed;
        opacity: 0.7;
      }
    }

    .calendar-icon {
      width: 16px;
      height: 16px;
      color: #64748b;
      flex-shrink: 0;
    }

    .display-value {
      flex: 1;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;

      &--placeholder {
        color: #94a3b8;
      }
    }

    .clear-btn {
      background: transparent;
      border: none;
      color: #94a3b8;
      font-size: 16px;
      cursor: pointer;
      line-height: 1;
      padding: 0 2px;

      &:hover {
        color: #0f172a;
      }
    }

    .date-picker-popover {
      position: absolute;
      top: calc(100% + 4px);
      left: 0;
      z-index: 1000;
      box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.15), 0 8px 10px -6px rgba(0, 0, 0, 0.1);
      border-radius: 1rem;
      animation: popoverFade 0.15s cubic-bezier(0.23, 1, 0.32, 1);
    }

    @keyframes popoverFade {
      from { opacity: 0; transform: translateY(-4px); }
      to { opacity: 1; transform: translateY(0); }
    }
  `]
})
export class DatePickerComponent implements ControlValueAccessor {
  private readonly elementRef = inject(ElementRef);

  @Input() mode: CalendarMode = 'single';
  @Input() placeholder = 'Seleccionar fecha…';
  @Input() disabled = false;

  @Output() dateChange = new EventEmitter<string | DateRange | undefined>();

  readonly isOpen = signal(false);
  readonly currentSelected = signal<Date | DateRange | undefined>(undefined);

  readonly displayValue = computed(() => {
    const val = this.currentSelected();
    if (!val) return '';
    if (val instanceof Date) {
      return formatDateToISO(val);
    }
    if (this.mode === 'range') {
      const range = val as DateRange;
      if (range.from && range.to) {
        return `${formatDateToISO(range.from)} al ${formatDateToISO(range.to)}`;
      }
      if (range.from) {
        return `${formatDateToISO(range.from)} - …`;
      }
    }
    return '';
  });

  private onChange: (val: any) => void = () => {};
  private onTouched: () => void = () => {};

  @HostListener('document:click', ['$event'])
  onClickOutside(event: MouseEvent): void {
    if (!this.elementRef.nativeElement.contains(event.target)) {
      this.isOpen.set(false);
    }
  }

  togglePopover(): void {
    if (this.disabled) return;
    this.isOpen.update((v) => !v);
  }

  clear(event: MouseEvent): void {
    event.stopPropagation();
    this.currentSelected.set(undefined);
    this.onChange(this.mode === 'range' ? { from: undefined, to: undefined } : '');
    this.dateChange.emit(undefined);
  }

  writeValue(val: any): void {
    if (!val) {
      this.currentSelected.set(undefined);
      return;
    }
    if (typeof val === 'string') {
      const parsed = new Date(val);
      if (!isNaN(parsed.getTime())) {
        this.currentSelected.set(parsed);
      }
    } else if (val instanceof Date) {
      this.currentSelected.set(val);
    } else if (typeof val === 'object' && ('from' in val || 'to' in val)) {
      this.currentSelected.set({
        from: val.from ? (val.from instanceof Date ? val.from : new Date(val.from)) : undefined,
        to: val.to ? (val.to instanceof Date ? val.to : new Date(val.to)) : undefined
      });
    }
  }

  registerOnChange(fn: any): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: any): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  onCalendarSelect(value: Date | Date[] | DateRange | undefined): void {
    if (this.mode === 'single') {
      const d = value as Date | undefined;
      this.currentSelected.set(d);
      const iso = d ? formatDateToISO(d) : '';
      this.onChange(iso);
      this.dateChange.emit(iso);
      this.isOpen.set(false);
      return;
    }

    if (this.mode === 'range') {
      const range = value as DateRange | undefined;
      this.currentSelected.set(range);
      if (range?.from && range?.to) {
        this.onChange(range);
        this.dateChange.emit(range);
        this.isOpen.set(false);
      } else {
        this.onChange(range);
      }
    }
  }
}
