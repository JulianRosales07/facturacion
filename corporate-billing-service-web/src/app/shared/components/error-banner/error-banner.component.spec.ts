import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ErrorBannerComponent as ErrorBanner } from './error-banner.component';

describe('ErrorBanner', () => {
  let component: ErrorBanner;
  let fixture: ComponentFixture<ErrorBanner>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ErrorBanner],
    }).compileComponents();

    fixture = TestBed.createComponent(ErrorBanner);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
