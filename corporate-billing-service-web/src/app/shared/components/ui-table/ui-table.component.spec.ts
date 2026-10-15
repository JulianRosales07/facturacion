import { ComponentFixture, TestBed } from '@angular/core/testing';
import { UiTableComponent as UiTable } from './ui-table.component';

describe('UiTable', () => {
  let component: UiTable<object>;
  let fixture: ComponentFixture<UiTable<object>>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UiTable],
    }).compileComponents();

    fixture = TestBed.createComponent(UiTable);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
