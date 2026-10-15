import {
  Component,
  Input,
  Output,
  EventEmitter,
  forwardRef,
  signal,
  computed,
  ElementRef,
  ViewChild,
  ChangeDetectionStrategy
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { CalendarMode, DateRange, CalendarEvent, DayCellState } from './calendar.types';

export const MONTH_NAMES = [
  'Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
  'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'
];

export const MONTH_NAMES_EN = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December'
];

export const WEEKDAY_LABELS_SUN = ['Do', 'Lu', 'Ma', 'Mi', 'Ju', 'Vi', 'Sá'];
export const WEEKDAY_LABELS_MON = ['Lu', 'Ma', 'Mi', 'Ju', 'Vi', 'Sá', 'Do'];

export function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

export function isSameDay(a?: Date, b?: Date): boolean {
  if (!a || !b) return false;
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

export function isSameMonth(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth();
}

export function addMonths(date: Date, amount: number): Date {
  return new Date(date.getFullYear(), date.getMonth() + amount, 1);
}

export function isBefore(a: Date, b: Date): boolean {
  return startOfDay(a).getTime() < startOfDay(b).getTime();
}

export function isWithinRange(date: Date, range: DateRange): boolean {
  if (!range.from || !range.to) return false;
  const t = startOfDay(date).getTime();
  const from = startOfDay(range.from).getTime();
  const to = startOfDay(range.to).getTime();
  return t > Math.min(from, to) && t < Math.max(from, to);
}

export function buildMonthGrid(month: Date, weekStartsOn: 0 | 1): Date[] {
  const firstOfMonth = new Date(month.getFullYear(), month.getMonth(), 1);
  const firstWeekday = firstOfMonth.getDay();
  const leading = weekStartsOn === 1 ? (firstWeekday + 6) % 7 : firstWeekday;
  const gridStart = new Date(firstOfMonth);
  gridStart.setDate(gridStart.getDate() - leading);

  return Array.from({ length: 42 }, (_, i) => {
    const d = new Date(gridStart);
    d.setDate(gridStart.getDate() + i);
    return d;
  });
}

@Component({
  selector: 'app-calendar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './calendar.component.html',
  styleUrls: ['./calendar.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => CalendarComponent),
      multi: true
    }
  ]
})
export class CalendarComponent implements ControlValueAccessor {
  @Input() mode: CalendarMode = 'single';
  @Input() weekStartsOn: 0 | 1 = 0; // 0 = Domingo, 1 = Lunes
  @Input() quickNav = true;
  @Input() disabledFn?: (date: Date) => boolean;
  @Input() bookedDates?: Date[];
  @Input() events?: CalendarEvent[];
  @Input() className = '';

  @Input() set month(value: Date | undefined) {
    if (value) {
      this.displayedMonthSignal.set(startOfDay(value));
    }
  }

  @Input() set defaultMonth(value: Date | undefined) {
    if (value && !this.displayedMonthSignal()) {
      this.displayedMonthSignal.set(startOfDay(value));
    }
  }

  @Input() set selected(value: Date | Date[] | DateRange | undefined) {
    this.selectedSignal.set(value);
  }

  @Output() select = new EventEmitter<Date | Date[] | DateRange | undefined>();
  @Output() monthChange = new EventEmitter<Date>();

  @ViewChild('dayGrid') dayGridRef?: ElementRef<HTMLDivElement>;

  readonly today = startOfDay(new Date());
  readonly displayedMonthSignal = signal<Date>(startOfDay(new Date()));
  readonly selectedSignal = signal<Date | Date[] | DateRange | undefined>(undefined);
  readonly navViewSignal = signal<'days' | 'months' | 'years'>('days');
  readonly directionSignal = signal<number>(1);
  readonly yearAnchorSignal = signal<number>(new Date().getFullYear());

  readonly monthNames = MONTH_NAMES;
  readonly weekdayLabels = computed(() =>
    this.weekStartsOn === 1 ? WEEKDAY_LABELS_MON : WEEKDAY_LABELS_SUN
  );

  readonly grid = computed(() =>
    buildMonthGrid(this.displayedMonthSignal(), this.weekStartsOn)
  );

  readonly weeks = computed(() => {
    const g = this.grid();
    const rows: Date[][] = [];
    for (let i = 0; i < g.length; i += 7) {
      rows.push(g.slice(i, i + 7));
    }
    return rows;
  });

  readonly monthLabel = computed(() => {
    const m = this.displayedMonthSignal();
    return `${MONTH_NAMES[m.getMonth()]} de ${m.getFullYear()}`;
  });

  readonly yearsList = computed(() => {
    const anchor = this.yearAnchorSignal();
    return Array.from({ length: 12 }, (_, i) => anchor - 5 + i);
  });

  private onChange: (val: any) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(val: any): void {
    if (val) {
      if (typeof val === 'string') {
        const parsed = new Date(val);
        if (!isNaN(parsed.getTime())) {
          this.selectedSignal.set(parsed);
          this.displayedMonthSignal.set(startOfDay(parsed));
          return;
        }
      }
      this.selectedSignal.set(val);
      if (val instanceof Date) {
        this.displayedMonthSignal.set(startOfDay(val));
      } else if (val.from instanceof Date) {
        this.displayedMonthSignal.set(startOfDay(val.from));
      }
    } else {
      this.selectedSignal.set(undefined);
    }
  }

  registerOnChange(fn: any): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: any): void {
    this.onTouched = fn;
  }

  setNavView(view: 'days' | 'months' | 'years'): void {
    if (view === 'years') {
      this.yearAnchorSignal.set(this.displayedMonthSignal().getFullYear());
    }
    this.navViewSignal.set(view);
  }

  openYearsView(): void {
    this.setNavView('years');
  }

  goToMonth(offset: number): void {
    const next = addMonths(this.displayedMonthSignal(), offset);
    this.directionSignal.set(offset >= 0 ? 1 : -1);
    this.displayedMonthSignal.set(next);
    this.monthChange.emit(next);
  }

  changeYearAnchor(offset: number): void {
    this.yearAnchorSignal.update((y) => y + offset);
  }

  selectMonth(monthIndex: number): void {
    const cur = this.displayedMonthSignal();
    const next = new Date(cur.getFullYear(), monthIndex, 1);
    this.directionSignal.set(1);
    this.displayedMonthSignal.set(next);
    this.monthChange.emit(next);
    this.navViewSignal.set('days');
  }

  selectYear(year: number): void {
    const cur = this.displayedMonthSignal();
    const next = new Date(year, cur.getMonth(), 1);
    this.directionSignal.set(1);
    this.displayedMonthSignal.set(next);
    this.monthChange.emit(next);
    this.navViewSignal.set('months');
  }

  isDisabled(date: Date): boolean {
    if (this.bookedDates?.some((b) => isSameDay(b, date))) return true;
    return this.disabledFn ? this.disabledFn(date) : false;
  }

  isBooked(date: Date): boolean {
    return this.bookedDates?.some((b) => isSameDay(b, date)) ?? false;
  }

  eventFor(date: Date): CalendarEvent | undefined {
    return this.events?.find((e) => isSameDay(e.date, date));
  }

  cellState(date: Date): DayCellState {
    const displayed = this.displayedMonthSignal();
    const outsideMonth = !isSameMonth(date, displayed);
    const isToday = isSameDay(date, this.today);
    const isBookedDay = this.isBooked(date);
    const event = this.eventFor(date);
    const isDayDisabled = this.isDisabled(date);

    let isSelected = false;
    let isRangeStart = false;
    let isRangeEnd = false;
    let isInRange = false;

    const current = this.selectedSignal();

    if (this.mode === 'single') {
      isSelected = isSameDay(current as Date | undefined, date);
    } else if (this.mode === 'multiple' && Array.isArray(current)) {
      isSelected = current.some((d) => isSameDay(d, date));
    } else if (this.mode === 'range' && current && !Array.isArray(current) && !(current instanceof Date)) {
      const range = current as DateRange;
      isRangeStart = isSameDay(range.from, date);
      isRangeEnd = isSameDay(range.to, date);
      isInRange = isWithinRange(date, range);
      isSelected = isRangeStart || isRangeEnd;
    }

    return {
      date,
      outsideMonth,
      isToday,
      isBooked: isBookedDay,
      event,
      isDisabled: isDayDisabled,
      isSelected,
      isRangeStart,
      isRangeEnd,
      isInRange,
      dateKey: date.toDateString()
    };
  }

  handleSelectDay(date: Date): void {
    if (this.isDisabled(date)) return;

    this.onTouched();

    if (this.mode === 'single') {
      const current = this.selectedSignal() as Date | undefined;
      const next = isSameDay(current, date) ? undefined : date;
      this.selectedSignal.set(next);
      this.onChange(next);
      this.select.emit(next);
      return;
    }

    if (this.mode === 'multiple') {
      const current = (this.selectedSignal() as Date[] | undefined) ?? [];
      const exists = current.some((d) => isSameDay(d, date));
      const next = exists ? current.filter((d) => !isSameDay(d, date)) : [...current, date];
      this.selectedSignal.set(next);
      this.onChange(next);
      this.select.emit(next);
      return;
    }

    // range mode
    const currentRange = (this.selectedSignal() as DateRange | undefined) ?? {};
    const from = currentRange.from;
    const to = currentRange.to;
    let next: DateRange;

    if (!from || (from && to)) {
      next = { from: date, to: undefined };
    } else if (isBefore(date, from)) {
      next = { from: date, to: from };
    } else {
      next = { from, to: date };
    }

    this.selectedSignal.set(next);
    this.onChange(next);
    this.select.emit(next);
  }

  onKeyDown(event: KeyboardEvent): void {
    const target = event.target as HTMLElement;
    const rawDate = target?.dataset?.['date'];
    if (!rawDate) return;

    const deltas: Record<string, number> = {
      ArrowLeft: -1,
      ArrowRight: 1,
      ArrowUp: -7,
      ArrowDown: 7
    };

    let targetDate: Date | undefined;

    if (event.key in deltas) {
      const current = new Date(rawDate);
      current.setDate(current.getDate() + deltas[event.key]);
      targetDate = current;
    } else if (event.key === 'Home' || event.key === 'End') {
      const current = new Date(rawDate);
      const weekday = this.weekStartsOn === 1 ? (current.getDay() + 6) % 7 : current.getDay();
      current.setDate(current.getDate() + (event.key === 'Home' ? -weekday : 6 - weekday));
      targetDate = current;
    } else if (event.key === 'PageUp' || event.key === 'PageDown') {
      event.preventDefault();
      const current = new Date(rawDate);
      targetDate = addMonths(current, event.key === 'PageUp' ? -1 : 1);
      const daysInTargetMonth = new Date(targetDate.getFullYear(), targetDate.getMonth() + 1, 0).getDate();
      targetDate.setDate(Math.min(current.getDate(), daysInTargetMonth));
    }

    if (!targetDate) return;
    event.preventDefault();

    const displayed = this.displayedMonthSignal();
    if (!isSameMonth(targetDate, displayed)) {
      this.displayedMonthSignal.set(new Date(targetDate.getFullYear(), targetDate.getMonth(), 1));
      setTimeout(() => {
        const iso = targetDate!.toDateString();
        (this.dayGridRef?.nativeElement?.querySelector(`[data-date-key="${iso}"]`) as HTMLElement)?.focus();
      }, 50);
      return;
    }

    const iso = targetDate.toDateString();
    (this.dayGridRef?.nativeElement?.querySelector(`[data-date-key="${iso}"]`) as HTMLElement)?.focus();
  }
}
