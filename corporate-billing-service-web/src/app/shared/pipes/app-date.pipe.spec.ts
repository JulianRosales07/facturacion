import { AppDatePipe } from './app-date.pipe';

describe('AppDatePipe', () => {
  it('create an instance', () => {
    const pipe = new AppDatePipe();
    expect(pipe).toBeTruthy();
  });
});


describe('Civil invoice dates',()=>{
 it('preserves the calendar day instead of shifting it to the previous day',()=>{expect(new AppDatePipe().transform('2026-09-01')).toBe('01/09/2026');});
 it('does not normalize an impossible date',()=>{expect(new AppDatePipe().transform('2026-02-31')).toBe('-');});
});
