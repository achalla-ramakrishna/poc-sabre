import type { Cabin, Rule, RuleRequest } from '../api/types';
import { fromCodes, toCodes } from '../components/CodeListInput';

/** Form state keeps lists as editable text; converted to the API shape on save. */
export interface RateRow {
  fareBrand: string;
  pointsPerUnit: string;
  currency: string;
  roundingStep: number;
}

export interface RuleFormState {
  name: string;
  origins: string;
  destinations: string;
  cabins: Cabin[];
  fareBrands: string;
  bundles: string;
  pointsOfSale: string;
  rates: RateRow[];
  maxRedeemPercent: string;
  minPoints: string;
  effectiveFrom: string;
  effectiveTo: string;
}

export const ROUNDING_STEPS = [1, 10, 50, 100, 1000];

export function emptyForm(): RuleFormState {
  return {
    name: '',
    origins: '',
    destinations: '',
    cabins: ['ECONOMY'],
    fareBrands: '',
    bundles: '',
    pointsOfSale: '',
    rates: [{ fareBrand: '', pointsPerUnit: '', currency: 'AUD', roundingStep: 100 }],
    maxRedeemPercent: '70',
    minPoints: '0',
    effectiveFrom: new Date().toISOString().slice(0, 10),
    effectiveTo: '',
  };
}

export function formFromRule(r: Rule): RuleFormState {
  return {
    name: r.name,
    origins: fromCodes(r.scope.origins),
    destinations: fromCodes(r.scope.destinations),
    cabins: [...r.scope.cabins],
    fareBrands: fromCodes(r.scope.fareBrands),
    bundles: fromCodes(r.scope.bundles),
    pointsOfSale: fromCodes(r.scope.pointsOfSale),
    rates: r.rates.map((x) => ({
      fareBrand: x.fareBrand ?? '',
      pointsPerUnit: x.pointsPerUnit,
      currency: x.currency,
      roundingStep: x.roundingStep,
    })),
    maxRedeemPercent: String(r.limits.maxRedeemPercent),
    minPoints: String(r.limits.minPoints),
    effectiveFrom: r.effectiveFrom,
    effectiveTo: r.effectiveTo ?? '',
  };
}

export function toRequest(f: RuleFormState): RuleRequest {
  return {
    name: f.name.trim(),
    scope: {
      origins: toCodes(f.origins),
      destinations: toCodes(f.destinations),
      cabins: f.cabins,
      fareBrands: toCodes(f.fareBrands),
      bundles: toCodes(f.bundles),
      pointsOfSale: toCodes(f.pointsOfSale),
    },
    rates: f.rates.map((r) => ({
      fareBrand: r.fareBrand.trim() ? r.fareBrand.trim().toUpperCase() : null,
      pointsPerUnit: r.pointsPerUnit.trim(),
      currency: r.currency.trim().toUpperCase(),
      roundingStep: r.roundingStep,
    })),
    limits: { maxRedeemPercent: Number(f.maxRedeemPercent), minPoints: Number(f.minPoints) },
    effectiveFrom: f.effectiveFrom,
    effectiveTo: f.effectiveTo || null,
  };
}
