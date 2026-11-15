# Accuracy corpus split

Fixed on 2026-11-15 **before** any real-corpus replay or tuning. Rule: every outdoor ride on the
Karoo 3 with GPS, power and at least 30 min (26 of the 46 rides with positions; the other 178 FIT files
are indoor trainer rides without GPS) goes into the **tuning** set when its ISO week number is even and
into the **held-out** set when it is odd. Tuning decisions use the tuning set only; reported results are
the held-out set. Ride files and anything derived from their coordinates stay in git-ignored
`corpus/private/`; only the FIT file ids are listed here.

| ride id | ISO week | hours | set |
|---|---|---|---|
| a82d2e60-7ac6-4f76-a964-c59b2c23b5b5 | 15 | 3.39 | held-out |
| 48b22af9-d4fe-4ec7-ac41-39829ce3ad6d | 15 | 3.44 | held-out |
| 52dda966-d3ef-41b0-87e5-9f3fa477b398 | 16 | 1.45 | tuning |
| ef65009d-cca7-460b-bb2f-b897ec59ca72 | 16 | 4.63 | tuning |
| ef88357f-e26e-4bf8-aa1f-900626605b06 | 16 | 2.46 | tuning |
| ea6af5b9-d146-40bc-b0b2-c7848ab6b29a | 17 | 4.21 | held-out |
| 511eeac9-c488-436e-8590-48aa7cc69a38 | 17 | 3.62 | held-out |
| 3e71b8ee-3d75-44f6-bf8a-462192ac93b2 | 18 | 4.15 | tuning |
| 5b3b1a9f-39e1-4813-a1d3-338368f73523 | 20 | 1.93 | tuning |
| 7ecc5319-1980-470d-8935-735f9616511f | 21 | 4.78 | held-out |
| c6ffbab8-acf3-4327-bec7-c6c8394264a5 | 22 | 2.10 | tuning |
| 1d865ecc-d238-4914-9460-f555a665e9cd | 22 | 5.08 | tuning |
| 90f355bf-8d1a-4dab-aab6-addeea15a85b | 23 | 3.42 | held-out |
| 9434d061-8473-43ce-a1e6-6f557bf0f1a1 | 25 | 0.90 | held-out |
| 95540f8a-43a1-430e-9fc2-5901938e7e50 | 26 | 4.28 | tuning |
| 8312af96-2717-493a-8b3d-15b1a645f607 | 27 | 5.22 | held-out |
| c5f2c897-5b44-4cfc-b366-c7d153b4644d | 29 | 4.88 | held-out |
| c4b0f5ba-4d5e-4216-be0d-00a1e9351302 | 38 | 4.33 | tuning |
| d1dcf5e4-94d6-456b-8105-ccb676ec9d3e | 42 | 4.04 | tuning |
| 37ab223d-c02a-4d96-bf66-28831d0d9f00 | 43 | 1.32 | held-out |
| 48ebb700-3fe8-476b-9c31-168ff8e3e20a | 43 | 3.28 | held-out |
| 134f007f-55d0-4570-9e89-d58ca2945b42 | 43 | 1.78 | held-out |
| 937c8c74-0cf8-4a5e-8eaa-36266863e0f1 | 45 | 2.00 | held-out |
| a17845af-a4de-4064-966b-5e9a45009a93 | 46 | 1.09 | tuning |
| e33ef3df-b365-48da-8acc-ed82fd65f269 | 46 | 2.56 | tuning |
| 0d095f57-66fa-473c-8733-ad4414da35be | 46 | 3.62 | tuning |

Tuning: 13 rides, 41.7 h. Held-out: 13 rides, 42.2 h.
