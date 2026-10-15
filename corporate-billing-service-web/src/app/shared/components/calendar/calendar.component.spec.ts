import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CalendarComponent } from './calendar.component';
import { DateRange } from './calendar.types';

describe('CalendarComponent', () => {
  let component: CalendarComponent;
  let fixture: ComponentFixture<CalendarComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CalendarComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(CalendarComponent);
    component = fixture.componentInstance;
    component.month = new Date(2026, 9, 1); // Oct 2026
    fixture.detectChanges();
  });

  it('should create the calendar component', () => {
    expect(component).toBeTruthy();
  });

  it('should display the correct month label', () => {
    expect(component.monthLabel()).toContain('2026');
  });

  it('should navigate to next and previous months', () => {
    const initialMonth = component.displayedMonthSignal().getMonth();
    component.goToMonth(1);
    expect(component.displayedMonthSignal().getMonth()).toBe((initialMonth + 1) % 12);

    component.goToMonth(-1);
    expect(component.displayedMonthSignal().getMonth()).toBe(initialMonth);
  });

  it('should switch between days, months, and years views', () => {
    expect(component.navViewSignal()).toBe('days');

    component.setNavView('months');
    expect(component.navViewSignal()).toBe('months');

    component.openYearsView();
    expect(component.navViewSignal()).toBe('years');

    component.selectMonth(4); // May
    expect(component.navViewSignal()).toBe('days');
    expect(component.displayedMonthSignal().getMonth()).toBe(4);
  });

  it('should select a day in single mode', () => {
    const targetDate = new Date(2026, 9, 15);
    let emitted: Date | undefined;
    component.select.subscribe((val) => (emitted = val as Date));

    component.handleSelectDay(targetDate);
    expect(component.selectedSignal()).toEqual(targetDate);
    expect(emitted).toEqual(targetDate);
  });

  it('should handle range mode selections', () => {
    component.mode = 'range';
    const day1 = new Date(2026, 9, 10);
    const day2 = new Date(2026, 9, 20);

    let emittedRange: DateRange | undefined;
    component.select.subscribe((val) => (emittedRange = val as DateRange));

    component.handleSelectDay(day1);
    expect(component.selectedSignal()).toEqual({ from: day1, to: undefined });

    component.handleSelectDay(day2);
    expect(component.selectedSignal()).toEqual({ from: day1, to: day2 });
    expect(emittedRange?.from).toEqual(day1);
    expect(emittedRange?.to).toEqual(day2);
  });

  it('should respect disabled and booked dates', () => {
    const booked = new Date(2026, 9, 12);
    component.bookedDates = [booked];

    expect(component.isBooked(booked)).toBe(true);
    expect(component.isDisabled(booked)).toBe(true);

    component.handleSelectDay(booked);
    expect(component.selectedSignal()).toBeUndefined();
  });
});
