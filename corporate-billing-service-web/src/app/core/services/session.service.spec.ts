import { TestBed } from '@angular/core/testing';
import { SessionService as Session } from './session.service';

describe('Session', () => {
  let service: Session;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(Session);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
