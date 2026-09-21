import http from 'k6/http';
import { check } from 'k6';
export const options = {
    scenarios: { load: { executor: 'constant-vus', vus: Number(__ENV.VUS), duration: '30s' } },
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};
const params = { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } };
export default function () {
    const res = http.get(`${__ENV.BASE_URL}/api/v1/patients/${__ENV.PATIENT_ID}`, params);
    check(res, { 'status 200': (r) => r.status === 200 });
}
