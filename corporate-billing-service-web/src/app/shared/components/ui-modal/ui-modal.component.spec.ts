import { ComponentFixture, TestBed } from '@angular/core/testing';
import { UiModalComponent as UiModal } from './ui-modal.component';

describe('UiModal', () => {
  let component: UiModal;
  let fixture: ComponentFixture<UiModal>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UiModal],
    }).compileComponents();

    fixture = TestBed.createComponent(UiModal);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
