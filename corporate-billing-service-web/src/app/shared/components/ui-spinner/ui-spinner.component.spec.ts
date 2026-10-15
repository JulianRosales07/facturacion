import { ComponentFixture, TestBed } from '@angular/core/testing';
import { UiSpinnerComponent as UiSpinner } from './ui-spinner.component';

describe('UiSpinner', () => {
  let component: UiSpinner;
  let fixture: ComponentFixture<UiSpinner>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UiSpinner],
    }).compileComponents();

    fixture = TestBed.createComponent(UiSpinner);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
