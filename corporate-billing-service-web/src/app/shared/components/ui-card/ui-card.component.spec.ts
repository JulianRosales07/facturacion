import { ComponentFixture, TestBed } from '@angular/core/testing';
import { UiCardComponent as UiCard } from './ui-card.component';

describe('UiCard', () => {
  let component: UiCard;
  let fixture: ComponentFixture<UiCard>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UiCard],
    }).compileComponents();

    fixture = TestBed.createComponent(UiCard);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
