export type CalendarMode = 'single' | 'range' | 'multiple';

export interface DateRange {
  from?: Date;
  to?: Date;
}

export interface CalendarEvent {
  date: Date;
  label?: string;
}

export interface DayCellState {
  date: Date;
  outsideMonth: boolean;
  isToday: boolean;
  isBooked: boolean;
  event?: CalendarEvent;
  isDisabled: boolean;
  isSelected: boolean;
  isRangeStart: boolean;
  isRangeEnd: boolean;
  isInRange: boolean;
  dateKey: string;
}
