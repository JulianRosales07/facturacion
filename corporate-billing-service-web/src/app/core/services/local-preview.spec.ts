import { afterEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { isLocalPreview } from './local-preview';

describe('local preview boundary', () => {
  const original = { ...environment };
  afterEach(() => Object.assign(environment, original));

  it('allows a local development build without an SSO session', () => {
    Object.assign(environment, { production: false, localPreview: true });
    expect(isLocalPreview('localhost')).toBe(true);
    expect(isLocalPreview('127.0.0.1')).toBe(true);
    expect(isLocalPreview('company.example')).toBe(false);
  });

  it('never enables preview in production, even on localhost', () => {
    Object.assign(environment, { production: true, localPreview: true });
    expect(isLocalPreview('localhost')).toBe(false);
  });

  it('can be disabled to test real SSO locally', () => {
    Object.assign(environment, { production: false, localPreview: false });
    expect(isLocalPreview('localhost')).toBe(false);
  });
});
