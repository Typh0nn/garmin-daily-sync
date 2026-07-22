import os
import json
import datetime
from garminconnect import Garmin
from github import Github

def main():
    email = os.environ.get("GARMIN_EMAIL")
    password = os.environ.get("GARMIN_PASSWORD")
    if not email or not password:
        print("Brak danych logowania do Garmin")
        return

    api = Garmin(email=email, password=password)
    api.login()
    print("Garmin zalogowany")

    # Pobieramy ostatnie 200 aktywnosci
    activities = api.get_activities(0, 200)
    print(f"Pobrano {len(activities)} aktywnosci")

    # Filtrowanie ostatnich 2 miesiecy i basenowych (lap_swimming)
    two_months_ago = datetime.datetime.now() - datetime.timedelta(days=60)
    
    pool_swims = []
    for act in activities:
        # activityType -> typeKey == lap_swimming
        act_type = act.get("activityType", {}).get("typeKey", "")
        if act_type == "lap_swimming" or "swimming" in act_type:
            # check date
            start_time_str = act.get("startTimeLocal", "")
            if start_time_str:
                try:
                    act_date = datetime.datetime.strptime(start_time_str, "%Y-%m-%d %H:%M:%S")
                    if act_date >= two_months_ago:
                        pool_swims.append(act)
                except Exception as e:
                    print(f"Error parsing date {start_time_str}: {e}")
                    pass

    print(f"Znaleziono {len(pool_swims)} treningow na basenie z ostatnich 2 miesiecy")

    repo_name = os.environ.get("GITHUB_REPOSITORY")
    gh_token = os.environ.get("GITHUB_TOKEN")
    if repo_name and gh_token:
        repo = Github(gh_token).get_repo(repo_name)
        content = json.dumps(pool_swims, indent=2)
        try:
            file = repo.get_contents("data/swimming.json")
            repo.update_file("data/swimming.json", "Update swimming data", content, file.sha)
            print("Zaktualizowano plik data/swimming.json na GitHub")
        except:
            repo.create_file("data/swimming.json", "Create swimming data", content)
            print("Utworzono plik data/swimming.json na GitHub")

if __name__ == "__main__":
    main()
