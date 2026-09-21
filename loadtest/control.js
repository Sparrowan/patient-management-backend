// Control run: NOT a test of the application.
//
// It measures the ceiling of everything that is not our code -- k6 itself, the Docker network, the
// macOS VM boundary, and Spring's servlet stack with no database, no auth and no business logic in
// the path. Every later number is only meaningful relative to this one: if a real endpoint lands
// near this ceiling, the platform is the constraint and tuning the service is wasted effort.
import http from 'k6/http';
import { check } from 'k6';

export const options = {
    scenarios: {
        ramp: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 50 },
                { duration: '30s', target: 200 },
                { duration: '20s', target: 200 },
            ],
        },
    },
    // Report percentiles, not just averages: an average hides the tail, and the tail is what users
    // actually experience.
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const BASE = __ENV.BASE_URL;

export default function () {
    const res = http.get(`${BASE}/actuator/health`);
    check(res, { 'status 200': (r) => r.status === 200 });
}
